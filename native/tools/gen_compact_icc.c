#include <lcms2.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define NG 17
static unsigned char lut[NG*NG*NG*4];

static void read_lut(const char* path){
 FILE* f=fopen(path,"rb"); if(!f){fprintf(stderr,"no lut\n");exit(3);}
 if(fread(lut,1,sizeof(lut),f)!=sizeof(lut)){fprintf(stderr,"short lut\n");exit(3);} fclose(f);
}
static void lerp_lut(float r,float g,float b,float* out){
 int i0=(int)floorf(r* (NG-1)); if(i0<0)i0=0; if(i0>NG-2)i0=NG-2;
 int j0=(int)floorf(g* (NG-1)); if(j0<0)j0=0; if(j0>NG-2)j0=NG-2;
 int k0=(int)floorf(b* (NG-1)); if(k0<0)k0=0; if(k0>NG-2)k0=NG-2;
 float fr=r*(NG-1)-i0, fg=g*(NG-1)-j0, fb=b*(NG-1)-k0;
 for(int ch=0;ch<4;ch++){
  float v=0;
  for(int di=0;di<2;di++)for(int dj=0;dj<2;dj++)for(int dk=0;dk<2;dk++){
   int i=i0+di,j=j0+dj,k=k0+dk;
   float w=(di?fr:1-fr)*(dj?fg:1-fg)*(dk?fb:1-fb);
   v+=w*lut[((k*NG+j)*NG+i)*4+ch];
  }
  out[ch]=v;
 }
}
// Generates the bundled CMYK profile from a 17^3 RGB->CMYK calibration
// table and the CC0 CGATS001Compat A2B data. Build:
//   cc -O2 native/tools/gen_compact_icc.c $(pkg-config --cflags --libs lcms2) -lm \
//       -o /tmp/gen_compact_icc
//   /tmp/gen_compact_icc jpdfium/src/main/resources/icc/JPDFiumCompactCmyk.icc \
//       native/tools/gs_cmyk_calibration.bin \
//       native/tools/CGATS001Compat-v2-micro.icc
int main(int argc,char**argv){
 if(argc!=4){fprintf(stderr,"usage: %s out.icc calibration.bin cgats.icc\n",argv[0]);return 2;}
 read_lut(argv[2]);
 const int GA=5,GB=33;
 const int agrid=GA*GA*GA*GA,bgrid=GB*GB*GB;
 cmsUInt16Number* a2b=calloc((size_t)agrid*3,sizeof(cmsUInt16Number));
 cmsUInt16Number* b2a=calloc((size_t)bgrid*4,sizeof(cmsUInt16Number));
 cmsUInt16Number* gamt=calloc((size_t)bgrid,sizeof(cmsUInt16Number));
 if(!a2b||!b2a||!gamt)return 3;
 cmsHPROFILE cgats=cmsOpenProfileFromFile(argv[3],"r");
 cmsHPROFILE lab=cmsCreateLab4Profile(NULL);
 cmsHPROFILE srgb=cmsCreate_sRGBProfile();
 if(!cgats||!lab||!srgb){fprintf(stderr,"profiles\n");return 4;}
 cmsHTRANSFORM toLab=cmsCreateTransform(cgats,TYPE_CMYK_16,lab,TYPE_Lab_16,INTENT_PERCEPTUAL,0);
 cmsHTRANSFORM labToRgb=cmsCreateTransform(lab,TYPE_Lab_16,srgb,TYPE_RGB_16,INTENT_PERCEPTUAL,0);
 if(!toLab||!labToRgb){fprintf(stderr,"transforms\n");return 5;}
 for(int c=0;c<GA;c++)for(int m=0;m<GA;m++)for(int y=0;y<GA;y++)for(int k=0;k<GA;k++){
  cmsUInt16Number in[4]={c*65535/(GA-1),m*65535/(GA-1),y*65535/(GA-1),k*65535/(GA-1)},out[3];
  cmsDoTransform(toLab,in,out,1);
  int o=(((c*GA+m)*GA+y)*GA+k)*3; a2b[o]=out[0];a2b[o+1]=out[1];a2b[o+2]=out[2];
 }
 for(int l=0;l<GB;l++)for(int a=0;a<GB;a++)for(int b=0;b<GB;b++){
  cmsUInt16Number labv[3]={l*65535/(GB-1),a*65535/(GB-1),b*65535/(GB-1)}, rgb16[3];
  cmsDoTransform(labToRgb,labv,rgb16,1);
  float rgb[3]={rgb16[0]/65535.0f,rgb16[1]/65535.0f,rgb16[2]/65535.0f}, cmyk[4];
  lerp_lut(rgb[0],rgb[1],rgb[2],cmyk);
  int o=((l*GB+a)*GB+b)*4;
  for(int i=0;i<4;i++){int v=(int)lrintf(cmyk[i]); if(v<0)v=0; if(v>255)v=255; b2a[o+i]=(cmsUInt16Number)(v*257);}
 }
 for(int l=0;l<GB;l++)for(int a=0;a<GB;a++)for(int b=0;b<GB;b++)gamt[(l*GB+a)*GB+b]=(cmsUInt16Number)(65535-l*65535/(GB-1));
 cmsHPROFILE h=cmsCreateProfilePlaceholder(NULL); if(!h)return 6;
 cmsSetProfileVersion(h,2.1);cmsSetDeviceClass(h,cmsSigOutputClass);cmsSetColorSpace(h,cmsSigCmykData);cmsSetPCS(h,cmsSigLabData);cmsSetHeaderRenderingIntent(h,INTENT_PERCEPTUAL);
 cmsPipeline*a=cmsPipelineAlloc(NULL,4,3),*a1=cmsPipelineAlloc(NULL,4,3),*b=cmsPipelineAlloc(NULL,3,4),*b1=cmsPipelineAlloc(NULL,3,4),*g=cmsPipelineAlloc(NULL,3,1);
 cmsPipelineInsertStage(a,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GA,4,3,a2b));
 cmsPipelineInsertStage(a1,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GA,4,3,a2b));
 cmsPipelineInsertStage(b,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GB,3,4,b2a));
 cmsPipelineInsertStage(b1,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GB,3,4,b2a));
 cmsPipelineInsertStage(g,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GB,3,1,gamt));
 cmsWriteTag(h,cmsSigAToB0Tag,a);cmsWriteTag(h,cmsSigAToB1Tag,a1);cmsWriteTag(h,cmsSigBToA0Tag,b);cmsWriteTag(h,cmsSigBToA1Tag,b1);cmsWriteTag(h,cmsSigGamutTag,g);cmsWriteTag(h,cmsSigMediaWhitePointTag,cmsD50_XYZ());
 cmsMLU*desc=cmsMLUalloc(NULL,1),*cprt=cmsMLUalloc(NULL,1);
 cmsMLUsetASCII(desc,"en","US","JPDFium Compact CMYK (GS calibrated)");
 cmsMLUsetASCII(cprt,"en","US","Derived from CC0 CGATS001Compat and a synthetic RGB calibration chart");
 cmsWriteTag(h,cmsSigProfileDescriptionTag,desc);cmsWriteTag(h,cmsSigCopyrightTag,cprt);
 return cmsSaveProfileToFile(h,argv[1])?0:7;
}
