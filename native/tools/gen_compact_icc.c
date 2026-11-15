/*
 * Generates the bundled JPDFiumCompactCmyk.icc output profile.
 *
 * Both directions of the profile are derived from the CC0
 * CGATS001Compat-v2-micro.icc input profile.  Its A2B0 table maps CMYK to
 * CIE Lab; the profile is sampled onto a small A2B CLUT, and the B2A CLUT is
 * built by inverting that same CMYK -> Lab response with a strong black
 * generation preference.  No Ghostscript data is used.
 *
 * Build:
 *   cc -O2 -Wall -Wextra native/tools/gen_compact_icc.c \
 *       $(pkg-config --cflags --libs lcms2) -lm -o /tmp/gen_compact_icc
 *
 * Run:
 *   /tmp/gen_compact_icc jpdfium/src/main/resources/icc/JPDFiumCompactCmyk.icc \
 *       native/tools/CGATS001Compat-v2-micro.icc
 */
#include <lcms2.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* Grid sizes.  B2A_GRID is odd so the neutral axis (a* = b* = 0) falls
 * exactly on a grid plane and trilinear interpolation cannot bleed chromatic
 * ink into neutral colours. */
#define A2B_GRID 5
#define B2A_GRID 11
#define K_RAMP_SAMPLES 257

/* Hard total area coverage cap (245%). */
#define TAC_LIMIT 2.45
/* Lab distance from the neutral axis that is still treated as neutral. */
#define NEUTRAL_LAB_EPS 3.0
/* Safety bound for the coordinate-descent solver. */
#define SOLVER_MAX_PASSES 400
/* Coarse CMYK sampling used to seed the inverse solver. */
#define CLOUD_GRID 9
/* Weight of the black-generation preference in the solver score. */
#define GCR_WEIGHT 0.1

typedef struct {
    cmsHTRANSFORM to_lab;
    double k_lightness[K_RAMP_SAMPLES];
} Solver;

static void lab16_to_double(const cmsUInt16Number* lab16, double* lab) {
    lab[0] = lab16[0] * 100.0 / 65535.0;
    lab[1] = lab16[1] * 255.0 / 65535.0 - 128.0;
    lab[2] = lab16[2] * 255.0 / 65535.0 - 128.0;
}

static void forward_lab(const Solver* solver, const double* ink, double* lab) {
    cmsUInt16Number in[4];
    cmsUInt16Number out[3];
    for (int i = 0; i < 4; i++) {
        double v = ink[i];
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;
        in[i] = (cmsUInt16Number)lrint(v * 65535.0);
    }
    cmsDoTransform(solver->to_lab, in, out, 1);
    lab16_to_double(out, lab);
}

/* Invert the neutral K ramp: return the K fraction whose pure-K lightness is
 * closest to the requested L*.  Below the darkest printable K the ramp is
 * clamped to solid K, which keeps near-black neutrals single-channel. */
static double k_for_lightness(const Solver* solver, double lightness) {
    if (lightness >= solver->k_lightness[0]) return 0.0;
    if (lightness <= solver->k_lightness[K_RAMP_SAMPLES - 1]) return 1.0;
    for (int i = 0; i + 1 < K_RAMP_SAMPLES; i++) {
        double upper = solver->k_lightness[i];
        double lower = solver->k_lightness[i + 1];
        if (lightness <= upper && lightness >= lower) {
            double span = upper - lower;
            double t = span > 0.0 ? (upper - lightness) / span : 0.0;
            return (i + t) / (double)(K_RAMP_SAMPLES - 1);
        }
    }
    return 1.0;
}

static double ink_sum(const double* ink) {
    return ink[0] + ink[1] + ink[2] + ink[3];
}

typedef struct {
    double ink[4];
    double lab[3];
} Candidate;

static int build_candidates(const Solver* solver, Candidate** out_candidates, size_t* out_count) {
    const size_t capacity = (size_t)CLOUD_GRID * CLOUD_GRID * CLOUD_GRID * CLOUD_GRID;
    Candidate* candidates = malloc(capacity * sizeof(Candidate));
    if (!candidates) return 1;
    size_t count = 0;
    for (int c = 0; c < CLOUD_GRID; c++) {
        for (int m = 0; m < CLOUD_GRID; m++) {
            for (int y = 0; y < CLOUD_GRID; y++) {
                for (int k = 0; k < CLOUD_GRID; k++) {
                    Candidate candidate;
                    candidate.ink[0] = c / (double)(CLOUD_GRID - 1);
                    candidate.ink[1] = m / (double)(CLOUD_GRID - 1);
                    candidate.ink[2] = y / (double)(CLOUD_GRID - 1);
                    candidate.ink[3] = k / (double)(CLOUD_GRID - 1);
                    if (ink_sum(candidate.ink) > TAC_LIMIT) continue;
                    forward_lab(solver, candidate.ink, candidate.lab);
                    candidates[count++] = candidate;
                }
            }
        }
    }
    *out_candidates = candidates;
    *out_count = count;
    return 0;
}

static double lab_distance2(const double* a, const double* b) {
    double dl = a[0] - b[0];
    double da = a[1] - b[1];
    double db = a[2] - b[2];
    return dl * dl + da * da + db * db;
}

static double solver_score(const Solver* solver, const double* ink, const double* target,
                           double* out_de2) {
    if (ink_sum(ink) > TAC_LIMIT) return HUGE_VAL;
    double lab[3];
    forward_lab(solver, ink, lab);
    double de2 = lab_distance2(lab, target);
    if (out_de2) *out_de2 = de2;
    double cmy = ink[0] + ink[1] + ink[2];
    return de2 + GCR_WEIGHT * cmy * cmy;
}

static void clamp_ink(double* ink) {
    for (int i = 0; i < 4; i++) {
        if (ink[i] < 0.0) ink[i] = 0.0;
        if (ink[i] > 1.0) ink[i] = 1.0;
    }
    double sum = ink_sum(ink);
    if (sum > TAC_LIMIT) {
        double cmy = ink[0] + ink[1] + ink[2];
        double room = TAC_LIMIT - ink[3];
        if (room <= 0.0) {
            ink[0] = ink[1] = ink[2] = 0.0;
            ink[3] = TAC_LIMIT;
        } else if (cmy > room) {
            double scale = room / cmy;
            ink[0] *= scale;
            ink[1] *= scale;
            ink[2] *= scale;
        }
    }
}

/* Gradient descent with a numerical gradient moves all four inks together,
 * which escapes the ridges that trap a single-channel coordinate descent. */
static void refine_gradient(const Solver* solver, const double* target, double* ink,
                            double* best_score) {
    const double epsilon = 1e-3;
    for (int iteration = 0; iteration < 120; iteration++) {
        double gradient[4];
        double norm = 0.0;
        for (int ch = 0; ch < 4; ch++) {
            double saved = ink[ch];
            ink[ch] = saved + epsilon;
            clamp_ink(ink);
            double plus = solver_score(solver, ink, target, NULL);
            ink[ch] = saved - epsilon;
            clamp_ink(ink);
            double minus = solver_score(solver, ink, target, NULL);
            ink[ch] = saved;
            gradient[ch] = (plus - minus) / (2.0 * epsilon);
            if (fabs(gradient[ch]) > norm) norm = fabs(gradient[ch]);
        }
        if (norm < 1e-6) break;
        double direction[4];
        for (int ch = 0; ch < 4; ch++) direction[ch] = -gradient[ch] / norm;

        int improved = 0;
        for (double step = 0.5; step > 1e-5; step *= 0.5) {
            double trial[4];
            for (int ch = 0; ch < 4; ch++) trial[ch] = ink[ch] + direction[ch] * step;
            clamp_ink(trial);
            double score = solver_score(solver, trial, target, NULL);
            if (score < *best_score - 1e-9) {
                for (int ch = 0; ch < 4; ch++) ink[ch] = trial[ch];
                *best_score = score;
                improved = 1;
                break;
            }
        }
        if (!improved) break;
    }
}

static void refine_coordinate(const Solver* solver, const double* target, double* ink,
                              double* best_score) {
    double step = 0.05;
    for (int pass = 0; pass < 200; pass++) {
        int moved = 0;
        for (int ch = 0; ch < 4; ch++) {
            double original = ink[ch];
            double best_value = original;
            double best = *best_score;
            for (int direction = -1; direction <= 1; direction += 2) {
                double candidate = original + direction * step;
                if (candidate < 0.0 || candidate > 1.0) continue;
                ink[ch] = candidate;
                double score = solver_score(solver, ink, target, NULL);
                if (score < best) {
                    best = score;
                    best_value = candidate;
                }
            }
            ink[ch] = best_value;
            if (best_value != original) {
                *best_score = best;
                moved = 1;
            }
        }
        if (!moved) {
            step *= 0.5;
            if (step < 1.0 / 65535.0) break;
        }
    }
}

static void solve_seed(const Solver* solver, const double* target, const double* seed, double* ink,
                       double* out_de2) {
    for (int i = 0; i < 4; i++) ink[i] = seed[i];
    clamp_ink(ink);
    double best = solver_score(solver, ink, target, NULL);
    refine_gradient(solver, target, ink, &best);
    refine_coordinate(solver, target, ink, &best);
    solver_score(solver, ink, target, out_de2);
}

/* Find a CMYK recipe for the target Lab value.  A coarse CMYK sampling
 * provides the seed, then gradient descent and a coordinate-descent polish
 * minimise the Lab distance.  The small CMY term prefers K over an equivalent
 * rich-black build, and the hard TAC limit keeps every node within budget. */
static void solve_ink(const Solver* solver, const Candidate* candidates, size_t candidate_count,
                      const double* target, double* ink, double* out_de2) {
    size_t nearest = 0;
    double nearest_distance = HUGE_VAL;
    for (size_t i = 0; i < candidate_count; i++) {
        double distance = lab_distance2(candidates[i].lab, target);
        if (distance < nearest_distance) {
            nearest_distance = distance;
            nearest = i;
        }
    }

    double best_ink[4];
    double best_de2 = HUGE_VAL;
    double trial_ink[4];

    solve_seed(solver, target, candidates[nearest].ink, trial_ink, &best_de2);
    for (int i = 0; i < 4; i++) best_ink[i] = trial_ink[i];

    double k_seed[4] = {0.0, 0.0, 0.0, k_for_lightness(solver, target[0])};
    double de2 = HUGE_VAL;
    solve_seed(solver, target, k_seed, trial_ink, &de2);
    if (de2 < best_de2) {
        best_de2 = de2;
        for (int i = 0; i < 4; i++) best_ink[i] = trial_ink[i];
    }

    for (int i = 0; i < 4; i++) ink[i] = best_ink[i];
    *out_de2 = best_de2;
}

static int build_a2b(const Solver* solver, cmsUInt16Number* table) {
    const int grid = A2B_GRID;
    for (int c = 0; c < grid; c++) {
        for (int m = 0; m < grid; m++) {
            for (int y = 0; y < grid; y++) {
                for (int k = 0; k < grid; k++) {
                    cmsUInt16Number in[4] = {
                        (cmsUInt16Number)(c * 65535 / (grid - 1)),
                        (cmsUInt16Number)(m * 65535 / (grid - 1)),
                        (cmsUInt16Number)(y * 65535 / (grid - 1)),
                        (cmsUInt16Number)(k * 65535 / (grid - 1)),
                    };
                    cmsUInt16Number out[3];
                    cmsDoTransform(solver->to_lab, in, out, 1);
                    size_t offset = (((size_t)c * grid + m) * grid + y) * grid + k;
                    table[offset * 3 + 0] = out[0];
                    table[offset * 3 + 1] = out[1];
                    table[offset * 3 + 2] = out[2];
                }
            }
        }
    }
    return 0;
}

/* The Lab zero point of the a and b axes is not exactly halfway through the
 * ICC 16-bit range.  A tiny smooth warp of the B2A input curves moves a = b =
 * 0 onto the centre CLUT node, so trilinear interpolation never bleeds
 * chromatic ink into neutrals. */
#define LAB_NEUTRAL_U (128.0 / 255.0)

static double lab_axis_warp(double u) {
    const double k = (0.5 - LAB_NEUTRAL_U) / (LAB_NEUTRAL_U * (1.0 - LAB_NEUTRAL_U));
    return u + k * u * (1.0 - u);
}

static double lab_axis_unwarp(double w) {
    const double k = (0.5 - LAB_NEUTRAL_U) / (LAB_NEUTRAL_U * (1.0 - LAB_NEUTRAL_U));
    double a = k;
    double b = 1.0 - k;
    double c = -w;
    return (-b + sqrt(b * b - 4.0 * a * c)) / (2.0 * a);
}

static cmsToneCurve* build_lab_axis_curve(void) {
    cmsUInt16Number table[256];
    for (int i = 0; i < 256; i++) {
        table[i] = (cmsUInt16Number)lrint(lab_axis_warp(i / 255.0) * 65535.0);
    }
    return cmsBuildTabulatedToneCurve16(NULL, 256, table);
}

static int build_b2a_and_gamut(const Solver* solver, const Candidate* candidates,
                               size_t candidate_count, cmsUInt16Number* b2a,
                               cmsUInt16Number* gamut) {
    const int grid = B2A_GRID;
    for (int l = 0; l < grid; l++) {
        for (int a = 0; a < grid; a++) {
            for (int b = 0; b < grid; b++) {
                double target[3];
                target[0] = 100.0 * l / (double)(grid - 1);
                target[1] = 255.0 * lab_axis_unwarp(a / (double)(grid - 1)) - 128.0;
                target[2] = 255.0 * lab_axis_unwarp(b / (double)(grid - 1)) - 128.0;

                double ink[4];
                double de2;
                int neutral =
                    fabs(target[1]) <= NEUTRAL_LAB_EPS && fabs(target[2]) <= NEUTRAL_LAB_EPS;
                if (neutral) {
                    ink[0] = 0.0;
                    ink[1] = 0.0;
                    ink[2] = 0.0;
                    ink[3] = k_for_lightness(solver, target[0]);
                    double lab[3];
                    forward_lab(solver, ink, lab);
                    double dl = lab[0] - target[0];
                    double da = lab[1] - target[1];
                    double db = lab[2] - target[2];
                    de2 = dl * dl + da * da + db * db;
                } else {
                    solve_ink(solver, candidates, candidate_count, target, ink, &de2);
                }
                (void)de2;

                size_t offset = ((size_t)l * grid + a) * grid + b;
                for (int i = 0; i < 4; i++) {
                    b2a[offset * 4 + i] = (cmsUInt16Number)lrint(ink[i] * 65535.0);
                }
                /* Every point is treated as in gamut.  This mirrors the
                 * generic nature of the fallback and, importantly, never
                 * marks the dark neutral axis out of gamut. */
                gamut[offset] = 0;
            }
        }
    }
    return 0;
}

static int build_k_ramp(Solver* solver) {
    for (int i = 0; i < K_RAMP_SAMPLES; i++) {
        double k = (double)i / (double)(K_RAMP_SAMPLES - 1);
        double ink[4] = {0.0, 0.0, 0.0, k};
        double lab[3];
        forward_lab(solver, ink, lab);
        solver->k_lightness[i] = lab[0];
    }
    /* The ramp must be monotonic for the inverse lookup to be meaningful. */
    for (int i = 1; i < K_RAMP_SAMPLES; i++) {
        if (solver->k_lightness[i] > solver->k_lightness[i - 1] + 1e-6) {
            fprintf(stderr, "CGATS K ramp is not monotonic at sample %d\n", i);
            return 1;
        }
    }
    return 0;
}

static void log_error(cmsContext context, cmsUInt32Number code, const char* text) {
    (void)context;
    fprintf(stderr, "lcms error %u: %s\n", code, text);
}

/* LittleCMS stamps the current time and a content hash into the header.
 * Clear both so regenerating the profile produces byte-identical output. */
static int normalize_header(const char* path) {
    FILE* file = fopen(path, "r+b");
    if (!file) return 1;
    unsigned char zeros[16] = {0};
    if (fseek(file, 24, SEEK_SET) != 0 || fwrite(zeros, 1, 12, file) != 12 ||
        fseek(file, 84, SEEK_SET) != 0 || fwrite(zeros, 1, 16, file) != 16) {
        fclose(file);
        return 1;
    }
    return fclose(file) == 0 ? 0 : 1;
}

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s out.icc cgats.icc\n", argv[0]);
        return 2;
    }

    cmsSetLogErrorHandler(log_error);
    int status = 1;
    cmsHPROFILE cgats = NULL;
    cmsHPROFILE lab = NULL;
    cmsHPROFILE out = NULL;
    cmsHTRANSFORM to_lab = NULL;
    cmsPipeline* a2b_pipeline = NULL;
    cmsPipeline* b2a_pipeline = NULL;
    cmsPipeline* gamut_pipeline = NULL;
    cmsToneCurve* identity_curve = NULL;
    cmsToneCurve* lab_axis_curve = NULL;
    cmsMLU* description = NULL;
    cmsMLU* copyright = NULL;
    cmsUInt16Number* a2b = NULL;
    cmsUInt16Number* b2a = NULL;
    cmsUInt16Number* gamut = NULL;
    Candidate* candidates = NULL;
    size_t candidate_count = 0;

    const size_t a2b_entries = (size_t)A2B_GRID * A2B_GRID * A2B_GRID * A2B_GRID;
    const size_t b2a_entries = (size_t)B2A_GRID * B2A_GRID * B2A_GRID;

    cgats = cmsOpenProfileFromFile(argv[2], "r");
    lab = cmsCreateLab4Profile(NULL);
    if (!cgats || !lab) {
        fprintf(stderr, "could not open input profiles\n");
        goto cleanup;
    }
    if (cmsGetColorSpace(cgats) != cmsSigCmykData || cmsGetPCS(cgats) != cmsSigLabData) {
        fprintf(stderr, "input profile must be a CMYK -> Lab profile\n");
        goto cleanup;
    }
    to_lab =
        cmsCreateTransform(cgats, TYPE_CMYK_16, lab, TYPE_Lab_16, INTENT_RELATIVE_COLORIMETRIC, 0);
    if (!to_lab) {
        fprintf(stderr, "could not create CMYK -> Lab transform\n");
        goto cleanup;
    }

    Solver solver;
    solver.to_lab = to_lab;
    if (build_k_ramp(&solver) != 0) goto cleanup;

    a2b = calloc(a2b_entries * 3, sizeof(cmsUInt16Number));
    b2a = calloc(b2a_entries * 4, sizeof(cmsUInt16Number));
    gamut = calloc(b2a_entries, sizeof(cmsUInt16Number));
    if (!a2b || !b2a || !gamut) {
        fprintf(stderr, "out of memory\n");
        goto cleanup;
    }

    if (build_candidates(&solver, &candidates, &candidate_count) != 0) {
        fprintf(stderr, "out of memory\n");
        goto cleanup;
    }

    build_a2b(&solver, a2b);
    build_b2a_and_gamut(&solver, candidates, candidate_count, b2a, gamut);

    out = cmsCreateProfilePlaceholder(NULL);
    if (!out) {
        fprintf(stderr, "could not create profile\n");
        goto cleanup;
    }
    cmsSetProfileVersion(out, 4.3);
    cmsSetDeviceClass(out, cmsSigOutputClass);
    cmsSetColorSpace(out, cmsSigCmykData);
    cmsSetPCS(out, cmsSigLabData);
    cmsSetHeaderRenderingIntent(out, INTENT_PERCEPTUAL);
    cmsSetHeaderFlags(out, 0);

    a2b_pipeline = cmsPipelineAlloc(NULL, 4, 3);
    b2a_pipeline = cmsPipelineAlloc(NULL, 3, 4);
    gamut_pipeline = cmsPipelineAlloc(NULL, 3, 1);
    identity_curve = cmsBuildGamma(NULL, 1.0);
    lab_axis_curve = build_lab_axis_curve();
    if (!a2b_pipeline || !b2a_pipeline || !gamut_pipeline || !identity_curve || !lab_axis_curve) {
        fprintf(stderr, "could not allocate pipelines\n");
        goto cleanup;
    }
    cmsToneCurve* in4[4] = {identity_curve, identity_curve, identity_curve, identity_curve};
    cmsToneCurve* in3[3] = {identity_curve, lab_axis_curve, lab_axis_curve};
    cmsToneCurve* out3[3] = {identity_curve, identity_curve, identity_curve};
    cmsToneCurve* out4[4] = {identity_curve, identity_curve, identity_curve, identity_curve};
    cmsToneCurve* out1[1] = {identity_curve};
    if (!cmsPipelineInsertStage(a2b_pipeline, cmsAT_END, cmsStageAllocToneCurves(NULL, 4, in4)) ||
        !cmsPipelineInsertStage(a2b_pipeline, cmsAT_END,
                                cmsStageAllocCLut16bit(NULL, A2B_GRID, 4, 3, a2b)) ||
        !cmsPipelineInsertStage(a2b_pipeline, cmsAT_END, cmsStageAllocToneCurves(NULL, 3, out3)) ||
        !cmsPipelineInsertStage(b2a_pipeline, cmsAT_END, cmsStageAllocToneCurves(NULL, 3, in3)) ||
        !cmsPipelineInsertStage(b2a_pipeline, cmsAT_END,
                                cmsStageAllocCLut16bit(NULL, B2A_GRID, 3, 4, b2a)) ||
        !cmsPipelineInsertStage(b2a_pipeline, cmsAT_END, cmsStageAllocToneCurves(NULL, 4, out4)) ||
        !cmsPipelineInsertStage(gamut_pipeline, cmsAT_END, cmsStageAllocToneCurves(NULL, 3, in3)) ||
        !cmsPipelineInsertStage(gamut_pipeline, cmsAT_END,
                                cmsStageAllocCLut16bit(NULL, B2A_GRID, 3, 1, gamut)) ||
        !cmsPipelineInsertStage(gamut_pipeline, cmsAT_END,
                                cmsStageAllocToneCurves(NULL, 1, out1))) {
        fprintf(stderr, "could not build CLUT stages\n");
        goto cleanup;
    }

    if (!cmsWriteTag(out, cmsSigAToB0Tag, a2b_pipeline) ||
        !cmsLinkTag(out, cmsSigAToB1Tag, cmsSigAToB0Tag) ||
        !cmsWriteTag(out, cmsSigBToA0Tag, b2a_pipeline) ||
        !cmsLinkTag(out, cmsSigBToA1Tag, cmsSigBToA0Tag) ||
        !cmsWriteTag(out, cmsSigGamutTag, gamut_pipeline) ||
        !cmsWriteTag(out, cmsSigMediaWhitePointTag, cmsD50_XYZ())) {
        fprintf(stderr, "could not write profile tags\n");
        goto cleanup;
    }

    description = cmsMLUalloc(NULL, 1);
    copyright = cmsMLUalloc(NULL, 1);
    if (!description || !copyright) {
        fprintf(stderr, "could not allocate profile text\n");
        goto cleanup;
    }
    cmsMLUsetASCII(description, "en", "US", "JPDFium Compact CMYK (CGATS001Compat)");
    cmsMLUsetASCII(copyright, "en", "US",
                   "Derived from the CC0 CGATS001Compat-v2-micro.icc profile; "
                   "free for any use");
    if (!cmsWriteTag(out, cmsSigProfileDescriptionTag, description) ||
        !cmsWriteTag(out, cmsSigCopyrightTag, copyright)) {
        fprintf(stderr, "could not write profile text tags\n");
        goto cleanup;
    }

    if (!cmsSaveProfileToFile(out, argv[1])) {
        fprintf(stderr, "could not save %s\n", argv[1]);
        goto cleanup;
    }
    if (normalize_header(argv[1]) != 0) {
        fprintf(stderr, "could not normalize %s\n", argv[1]);
        goto cleanup;
    }
    status = 0;

cleanup:
    if (description) cmsMLUfree(description);
    if (copyright) cmsMLUfree(copyright);
    if (gamut_pipeline) cmsPipelineFree(gamut_pipeline);
    if (b2a_pipeline) cmsPipelineFree(b2a_pipeline);
    if (a2b_pipeline) cmsPipelineFree(a2b_pipeline);
    if (lab_axis_curve) cmsFreeToneCurve(lab_axis_curve);
    if (identity_curve) cmsFreeToneCurve(identity_curve);
    if (out) cmsCloseProfile(out);
    if (to_lab) cmsDeleteTransform(to_lab);
    if (lab) cmsCloseProfile(lab);
    if (cgats) cmsCloseProfile(cgats);
    free(a2b);
    free(b2a);
    free(gamut);
    free(candidates);
    return status;
}
