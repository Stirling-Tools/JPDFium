# Shrink-only ProGuard profile for the published-library experiment.
# Do not enable obfuscation or optimization here: both can change public
# signatures or reflection/native-image metadata. The task is a measurement
# until the API/metadata keep surface is fully proven.
-dontobfuscate
-dontoptimize
-dontwarn
-ignorewarnings
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,LineNumberTable
-keep public class * { public protected *; }
-keep class stirling.software.jpdfium.doc.FileWriteSink { *; }
-keep class stirling.software.jpdfium.doc.PdfImageOptimizer$JpegFileAccess { *; }
-keep class stirling.software.jpdfium.panama.** { *; }
-keep class stirling.software.jpdfium.spi.** { *; }
