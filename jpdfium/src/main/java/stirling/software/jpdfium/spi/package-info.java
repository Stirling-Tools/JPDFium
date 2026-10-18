/**
 * Service provider interface for pluggable image codecs.
 *
 * <p>An {@link stirling.software.jpdfium.spi.ImageCodec} registered through
 * {@link java.util.ServiceLoader} becomes the default encoder and decoder. The
 * {@code jpdfium-vips} module provides a libvips-backed implementation that
 * adds HEIC, HEIF, AVIF, JXL, JPEG 2000, and plugin-free WebP writes. Without
 * one, JPDFium falls back to {@code javax.imageio}.
 *
 * @see stirling.software.jpdfium.spi.ImageCodec
 */
package stirling.software.jpdfium.spi;
