#include <lcms2.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

// Generates the bundled generic CMYK profile from the CC0 CGATS001Compat
// source profile. Build:
//   cc -O2 native/tools/gen_compact_icc.c $(pkg-config --cflags --libs lcms2) -lm \
//       -o /tmp/gen_compact_icc
//   /tmp/gen_compact_icc jpdfium/src/main/resources/icc/JPDFiumCompactCmyk.icc \
//       native/tools/CGATS001Compat-v2-micro.icc
int main(int argc,char**argv){
 if(argc!=3)return 2;
 const int GA=5,GB=11,GS=17;
 const int agrid=GA*GA*GA*GA,bgrid=GB*GB*GB,sgrid=GS*GS*GS*GS;
 cmsUInt16Number*a2b=calloc((size_t)agrid*3,sizeof(cmsUInt16Number));
 cmsUInt16Number*b2a=calloc((size_t)bgrid*4,sizeof(cmsUInt16Number));
 cmsUInt16Number*gamt=calloc((size_t)bgrid,sizeof(cmsUInt16Number));
 uint16_t*samples=malloc((size_t)sgrid*4*sizeof(uint16_t));
 uint16_t*slab=malloc((size_t)sgrid*3*sizeof(uint16_t));
 if(!a2b||!b2a||!gamt||!samples||!slab)return 3;
 cmsHPROFILE cgats=cmsOpenProfileFromFile(argv[2],"r"); cmsHPROFILE lab=cmsCreateLab4Profile(NULL);
 if(!cgats||!lab)return 4;
 cmsHTRANSFORM toLab=cmsCreateTransform(cgats,TYPE_CMYK_16,lab,TYPE_Lab_16,INTENT_RELATIVE_COLORIMETRIC,0);
 if(!toLab)return 5;
 for(int c=0;c<GS;c++)for(int m=0;m<GS;m++)for(int y=0;y<GS;y++)for(int k=0;k<GS;k++){
   int si=(((c*GS+m)*GS+y)*GS+k);
   uint16_t in[4]={c*65535/(GS-1),m*65535/(GS-1),y*65535/(GS-1),k*65535/(GS-1)};
   uint16_t out[3]; cmsDoTransform(toLab,in,out,1);
   for(int i=0;i<4;i++)samples[si*4+i]=in[i];
   for(int i=0;i<3;i++)slab[si*3+i]=out[i];
 }
 for(int c=0;c<GA;c++)for(int m=0;m<GA;m++)for(int y=0;y<GA;y++)for(int k=0;k<GA;k++){
   uint16_t in[4]={c*65535/(GA-1),m*65535/(GA-1),y*65535/(GA-1),k*65535/(GA-1)},out[3];
   cmsDoTransform(toLab,in,out,1);
   int o=(((c*GA+m)*GA+y)*GA+k)*3; for(int i=0;i<3;i++)a2b[o+i]=out[i];
 }
 for(int l=0;l<GB;l++)for(int a=0;a<GB;a++)for(int b=0;b<GB;b++){
   uint16_t target[3]={l*65535/(GB-1),a*65535/(GB-1),b*65535/(GB-1)};
   int best=0; double bestD=1e300;
   for(int si=0;si<sgrid;si++){
     double dl=(double)slab[si*3]-target[0],da=(double)slab[si*3+1]-target[1],db=(double)slab[si*3+2]-target[2];
     double d=dl*dl+da*da+db*db; if(d<bestD){bestD=d;best=si;}
   }
   uint16_t cmyk[4]={samples[best*4],samples[best*4+1],samples[best*4+2],samples[best*4+3]};
   for(int step=8192;step>=1;step/=2){int improved=1;while(improved){improved=0;for(int ch=0;ch<4;ch++)for(int dir=-1;dir<=1;dir+=2){uint16_t trial[4]={cmyk[0],cmyk[1],cmyk[2],cmyk[3]};int v=(int)trial[ch]+dir*step;if(v<0)v=0;if(v>65535)v=65535;trial[ch]=v;uint16_t out[3];cmsDoTransform(toLab,trial,out,1);double dl=(double)out[0]-target[0],da=(double)out[1]-target[1],db=(double)out[2]-target[2];double d=dl*dl+da*da+db*db;if(d+1e-9<bestD){bestD=d;for(int i=0;i<4;i++)cmyk[i]=trial[i];improved=1;}}}}
   int o=((l*GB+a)*GB+b)*4; for(int i=0;i<4;i++)b2a[o+i]=cmyk[i];
 }
 for(int l=0;l<GB;l++)for(int a=0;a<GB;a++)for(int b=0;b<GB;b++)gamt[(l*GB+a)*GB+b]=(uint16_t)(65535-l*65535/(GB-1));
 cmsHPROFILE h=cmsCreateProfilePlaceholder(NULL); if(!h)return 6;
 cmsSetProfileVersion(h,2.1);cmsSetDeviceClass(h,cmsSigOutputClass);cmsSetColorSpace(h,cmsSigCmykData);cmsSetPCS(h,cmsSigLabData);cmsSetHeaderRenderingIntent(h,INTENT_PERCEPTUAL);
 cmsPipeline*a=cmsPipelineAlloc(NULL,4,3),*a1=cmsPipelineAlloc(NULL,4,3),*b=cmsPipelineAlloc(NULL,3,4),*b1=cmsPipelineAlloc(NULL,3,4),*g=cmsPipelineAlloc(NULL,3,1);
 cmsPipelineInsertStage(a,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GA,4,3,a2b));cmsPipelineInsertStage(a1,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GA,4,3,a2b));cmsPipelineInsertStage(b,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GB,3,4,b2a));cmsPipelineInsertStage(b1,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GB,3,4,b2a));cmsPipelineInsertStage(g,cmsAT_BEGIN,cmsStageAllocCLut16bit(NULL,GB,3,1,gamt));
 cmsWriteTag(h,cmsSigAToB0Tag,a);cmsWriteTag(h,cmsSigAToB1Tag,a1);cmsWriteTag(h,cmsSigBToA0Tag,b);cmsWriteTag(h,cmsSigBToA1Tag,b1);cmsWriteTag(h,cmsSigGamutTag,g);cmsWriteTag(h,cmsSigMediaWhitePointTag,cmsD50_XYZ());
 cmsMLU*desc=cmsMLUalloc(NULL,1),*cprt=cmsMLUalloc(NULL,1);cmsMLUsetASCII(desc,"en","US","JPDFium Compact CMYK (CGATS compatible)");cmsMLUsetASCII(cprt,"en","US","CC0 profile derived from saucecontrol Compact ICC Profiles / CGATS001Compat-v2-micro.icc");cmsWriteTag(h,cmsSigProfileDescriptionTag,desc);cmsWriteTag(h,cmsSigCopyrightTag,cprt);
 return cmsSaveProfileToFile(h,argv[1])?0:7;
}
