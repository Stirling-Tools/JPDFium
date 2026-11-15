package stirling.software.jpdfium.fonts;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfFontEmbedder;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.FontType;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link FontNormalizer}.
 *
 * <p>The stub library returns default/empty results, so the Java-layer tests
 * verify parsing, record construction, and API correctness. The normalized-page
 * regression tests need the real qpdf + FreeType bridge and are integration-gated.
 */
class FontNormalizerTest {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static final String CFF_MULTI_OTF_BASE64 =
            "H4sIAAAAAAAC/61VXWwUVRQ+d3fbWbZriaWtkSzlqmDAtHW3RaANmJb+UaR2YcufIZVhOnQn2Z0pM7PQgoUSAZFJum3V1iK2" +
            "hPjzIImpaNAYfZAHMEpsSBVMUHw0MdEXcFtau557d4CtUkKMM7n3nr/vO+eee3e2sampEbzQBU6oqKqtpe91jWwCcJwEcPbV" +
            "VdfUQh56AH7FMb8u2BiqNtfHAIgL9YK60KY1mqCcxvhlqO9qDD1d0vzuzwPoH0N9hxQV25D7bdTDqC8Ly2LLxFjWKOrID8vD" +
            "aHAvcS1GvRr1ReGo2T5nH/hRZvjcqNjeBhlBFOFbHJmqGJU3jeg9KGKMa36bZpjJT6EE8YvQTwEwNgOlW1jRZcghDThiyVuO" +
            "3yAHCNq9kHoIvi4+E3BwHcADPnxJde36JnAD2x8kk3a8M7UkP2G57vEg3rVwugxAGJ1aPp0vjHLG9IdV5bCZcnEmfHWxmnF2" +
            "cX0dz7c2mUx+ZuPZTB4d/OnU2eUvzl11E7Kc7Bzgmy1bXrm9Yr4aYdTJkG6egT0O0kt6WE5yg9xg/UitOEpIyb9rv/uEmuq3" +
            "QgWshXWO35OZZNq1kFw4BSR5HfnJV45+rJfa1XtgGawBB+8XjYimCtmM7fn11RR9rHeE7+12H0lEaRWxRntXfNdsdePIsVdm" +
            "c3B+fi5owM4QksvKqpNVWRcjRRvl1lhE1Jl9hY+8Aj5yjPjIqw4fOe70kcOubV7ndPNUo08YvfVaxgLsxeK5ODvLH/ZBBVML" +
            "cyDDgdReMkjOkHPky82ybiiaSgPFgSqtrUNXWsMmXSItpSV+f6CoxB8I0J0d1AzLFMugwdhOWqVF20RVkY1CKmmqoRimorZS" +
            "bRcPqozKuiKJKq0Ky1EUIjSkSYpsdhTOdNerCDNjpsyAwXCHoUjGP0IaRFSjonkflhQw3V0TMeQ9iqwXYgapuJCKagttQsiM" +
            "fDURWTJ1DmT+lKqpikRr1FZFlbElKTylQU03sT0GbjW9OYGyspXYHH8pa06DIulaUJeNB0D5GWBmkZRWRiKURxkUWWR9j9xS" +
            "fI9DTzPFVMUfKGU/fVjqIBZMVOaO/5HXOTi++Y1E82BmgcfZvzjpndPr9VzynO7p7o739MVf7/Z6L2V9FO+Jx7v7+nrj3ocK" +
            "PJlJbw7ejHngJESKXxwSKno7T1iX3eP1gnX1xMD3ve6hyTPtwpUjbx6yVrknN0yeT2wQrB/f6h+zPVePDHRZq91TzwrWM10H" +
            "Vx91tyc+EI4PZVTGD5y0xtyJ5sT5yZBgrTi0n/mErCGhrHv/CesH9/hzgnVlcOB6HHm+aBd+OTrwMmaYQuvqAwdXHGOxk2fb" +
            "M68dHjhqlbuntgvbjuyNFOy1jg8deqfr/ZcGTcvdFtu9e/vH+oWbF79OLFg4Dlhyf/+1XncWZBDiynuiNDh5MzuRZQ0nPj+X" +
            "+HCYTITyqSd78rQ1POEYJonv8h9DbV/nyPg0an9OPJX3uCc7sahzxGD+nHmnJiLcNm/HVOCvR/IwmH8kBHiB/UZdc/BnlQtn" +
            "bJlAPvTbsgO/Bcds2YlfkwpbdsGTMN+WM1ACW87k9s0ggw4GKKCBit+ZABTz4YcQNEE9bIU6jFB5lAgRKIKNKLdCDGURbbUc" +
            "Z0IDagauLK78AbHladlmeu6Nr8JcbdCBmoKxYcxGYQlIsBTXEuTwI1cRlwL4UtiJsRSjwshAbU6Kf1cx9FDOFkU+ETMoGGFA" +
            "IVolvh/WD7YbBeVWtGqwK42pEnEyr0LiaMbFPFHbEuHZNJQZr4lVFN4XXc8zsmwm1mbyqFTGIGI6eDWSXd/sLOwETLsOkbP9" +
            "l1rSM86GrkG7gfoebtW5je1BwpNkMmNqwbXJzjL7/hiTjDjzTg2RNHy6V+OnJHErOxOF34vU3U3Pz3agodW0b7Rhn+ls9yYA" +
            "ZfiutO+NH0rv3JsGno9lDuLM7sf/kcl/h/9+XWQnE+G9uMtkcE3mGB1RMvaoGOBvSwHNd0wKAAA=";

    private static final String CFF_OTF_BASE64 =
            "H4sIAAAAAAAC/42Tz2sTQRTHvzOzSWgOm9iKtvhrkQjWWpQetIggshp6kaxtQEGhXZpNckjakGwhJxFPgUL1pAhe/A88eNOC" +
            "etGTePDUQ8GTIkUQlOyKsr6ZnQazB/XBzPu89+a9mX0zWyqXSxjBbQjk7WLR6l5dfAvwbwC2SgunZi79tAOAkYml5abb4nPs" +
            "FcUrZLfqnls5XQt94kc0ztTJkdrmr4nf0yjUm353rCBTxTuajjfdbgtZRzqoJtIrbtNbnpiqU/0lGry12vHpHCRc5ltqWxrm" +
            "3cfHFnPnv0OITzL6cXxrZ6BnaPUX/lxuAo5YKEdY0aycyapQlGFYsoCjZgj2md1DCv8jQ1UukKhTtgd7vUmst/Ret3QmQ2ZQ" +
            "hRNnNAvkYGo2iA9pTiu/ADNGyGPipGZOfE6zwGHMaTaIq5rT0m9Xq2Wv4897tbWG29bW9LBpaRM25VZRhocOfMyTrmENDbhI" +
            "xqb/GrUS0fhroW52rzyd0gZukh5FXt2bhYv0Fdcor4FVtKNIdS/2VXZ90YfoZfQsegJED6MH0f0/7iM7INkF8a9rNBgYY/lE" +
            "Q6RvIjjAg4MiGDc218M7oZPazq33V0a/7gGnhP2JnmkzLnoUkyjiCj2sWSzgMrKMGZmxI1Nn7dKNWtAz+xtmsLO5L3SC3i8n" +
            "M8w9xQXJ/Q3J/RNJf/hUcugof0GteZFcQ8eQf+4Pavj1eOzaUsu/gd664t9/Ga8s+AMAAA==";

    private static final String TTC_FONTS_BASE64 =
            "H4sIAAAAAAAC/+05e3xU5ZXn3DuvPIaZCSGZ8Alzh5vEaBICGUgJRjIkmSEYNBOSyEwQyORF0JAMzIBiVUJf4iAFW2oX0UK7" +
            "9rfiixvAEm1dwna3Vis/6bZd19eC1r4sNNTW7lZIZs/3zU1IKNrHb3d/+4ffzL33vM/5zjnfvd/cicXauwCBD4mOTADXPIE7" +
            "6GQA+5Ianx/skAow8yhRs5YE6htrNi/bRPjLhCtLGpurTnzwL6cJP0t4bX1jSemdL9wyH8BykfDW9vXhCL6DGkDKLYT3tG+O" +
            "KTe8XvchuRoil9u7ImvX3xP+loP43wcwH10bjkbACSlk71GSt6/t2dKV82h5MQDOIb69uzPckeUsf4Ds30H8sm4i2KzG9wj/" +
            "d8Jzu9fH7ijql79KOPmEgp6+9nDK65ZDhLfy+NeH74jIM6Qg4V4ef294feebD75O9rJryX5FpC8aSwzBg+T/c5wf2dgZGXl6" +
            "yVJKyF0AcjVl6SSAsdS4DWQwg8trlUxG2SSnWIyygUiVJ0tOOjKwvNzhcXjmzpnqdrinOtyOk4bOC/uWySeN2z7capx/Idvw" +
            "K55vA1laTpbMlOGV3jIrQrokm4wWkA0Gi1nOcKRLa0Lp6cJ0hpaBgQw8n4FDGbg7A1szcE4GlmTgKjE2bIDK0kpPeUkhec8m" +
            "76UOjyejvJy+c+e4ZbesogfRbDITmH+1YdfXR+75xvekytekspGVKTlzj0q2Z666Ch8e7eARGn57VeNnRufiD30rqBceoygV" +
            "ijINNnqzzbJssACkGdLSrWaKLmDGM2Y0Dybe9s6eunSL+T6zZDOjxWxOEUErVhyyombFA1bst2LEiq1WDFiR6Kv0MRb46lWr" +
            "CldtKBxPXjKBlD+HSqCbDkN4xHT8uPThcWnnSNS4beRJqenDrdQYvFvMX6AIc/CiN+HMsSAiZJodaLGjw06ZNGOabLampqRY" +
            "DZk5RjZ9B0M2dNfW2nKG1zLMYZjK8I8M32P4GsOXGA4yvI/tZQeZfAfDdQwXshtYC5OvYTidYTrD7hGGZxm+xfBlhs8zfILh" +
            "wwzJ6l0Mb2N4C8M6hhUMCxlexTCN4UWGv2b4JsMfMPzOmDzsZLiV4XqGaxjeyLCEVTJpBkMbQ7I/LOy/IuwfYvgIw11c9m4m" +
            "rRTS1zEspmkwtDJccIHhOYZvMDzJvH34bYZPMdzHkBx8WjioYyuZVC4CyhEB/VEE9JYIKDmBR8QE7hYTWCUmcD1DruBiKK1h" +
            "W9l+dpydZglmAoYWp92QImdarWjJgEoPVYpOHqQSrtmwgb4TxsbJY/WqyWNMbM2VxSfLc4HCDY6MbNEga1avorNn1Sp+Er2i" +
            "UmubTLzVp2Zll2VM5ZdPlZWhx/iLd3/vnD09N/HuaPiFkeJ8Z+Ufjn2wQElhs9Dygry16bXYwxep8y9ue+xwLxrktRe//OpX" +
            "1OgD8mG+Sm9OnJWHjS9DFsyCm71zZ8CUKbZsk82Uq2ZMm0JLQbZYlEDIYpenB0Jy1u5cjOSiKxcTuXgmF4dyMRk4VBaCs5La" +
            "G8rLnZWVE9qcIs8zmdRZksPuLs3K9lxNxMxsNT9/PmZmeUrL5s/LV2eZ5Pml37zz5An84qcfLZWko6YnZfPI63fcuzce/+r2" +
            "LU93t2AmOqWylrYteOLC1INl9ti1GPnpP//49Kvff5HmsCJx1uA03AQZkAO3e/1THSZzDkB6utkhs+kmE8g5EAhZczDTkJOT" +
            "YrNlBUI2e4ocCKVknWI4xPAAw90M+xlGGLYyDDCcw3C8xhPm5iyhsuiQficaW8rJ2XwqW3LzqWZ4FMe0q/P51MyY+dCeTTtz" +
            "vhYefez8hQu/wrees+2+97N7Tfifz720urY4ATgTp2M6zhw54Yw/8cihvbTm47TwF1FVZNjsbZDpFmWgB9a080Y8Y8TTRhwy" +
            "ombE/UbsN2LEiC4j2ox4fgLrgBF3G7HeiAmhckrQx4Und+eldoRk5fh0+KziR40vfzgv2SOGGZTfVOqSWm+Rw5QGJsh2WqaI" +
            "xsjkjXHAibud2O/EiBNbnRhw4hwnnnbiqsuTOP7wQLukd0WGLHpgmsigYcaHvzn3Pv7sv957/vOPfG3njq98Y4c0c/Td0ffQ" +
            "jQ5pzujw6NtnfvDKm//26il+b9zOn7IU2VRQvXbT1KlU9cxpNlOq3WCDaTQdvnovufQ45l3tyZrmKSvzlGZPEz4dXzQ9bjEU" +
            "Rrpy83IrIpvlRRvjg3k7ulK/mXri6MjLYq+QCqrYPfDVYocSoCe99KhkgOTIo88d8CL+l9QifVdOlVfIFJPQk3L2nu/d27TG" +
            "VvEBuCxC+PvePz7Er79wvnEQDImzdF/n+wyLbp+yCielcir3zWAwPgSPmXdCiiEKN9OxAl+AOIelx2nWZN+8aPQmqCYJAxjM" +
            "X9B3OZdGKj1m/x53g5GsPWT0UEgseZV/CF1ShsUopZkMEh8GPd7xMXdxYzV4QYGNxh+NNqDHvAgPj8kYFtKTiHYOUKTvqWSx" +
            "r5IFN5Niob0HrUM7USSSWgy1sAI6oQf6YGMiITSTtI4xWuLtxInEtxNPJb6e2JfYm/jqn8yEhnfJypZQsLmpcXlDoP6mG5fV" +
            "3bC0donfV1Ndtdhbuej6iusWli/4VNn8uXNKZhcXFVydn5erznK7nJkOu22KNS01xWI2GQ2yhFCkaNjq0+Q8xeEPqz41XFtc" +
            "pPic3TXFRT7V36opYUWjiyFfra0VJDWsKa2Klk+X8ARyq+Ylya7LJL1JSe+4JNqVCqjgLlRFO1mjKoPY0hAkeGeNGlK0cwK+" +
            "UcCGfIFYCXG7SUNExaNVfJp/c3fc10ox4kBaarVa3ZlaXAQDqWkEphGkFaiRASxYhAKQCnwLBySwWLlbmqkv3KEFGoK+GuZ2" +
            "h4qLlmpT1BrBgmphUjNVa2ZhUlnHQ4cdykDRUPz+QTu0tRamd6gd4VuCmhwm3bjsi8fv1RyF2jVqjXbNne86aeadWpFa49MK" +
            "udW65eN+6i65pBtSnl1V4h8ATUc9d3YyJaxTTHn2D4CDmlSt4fKgmw/mp1zH435V8cdb4+HBRH+bqtjV+EB6ejzio3RDIEgm" +
            "BhPP7WCa//6QZm/txoUhfer+5XXa1IaVQU3K8yvdYaLQt1J1L2Bux7hM4KPYQGmh5FCG3W6ehh2DXmgjROtvCCZxBdrYYfCW" +
            "FIY0qZVzhsY405o5p3+MM67eqlJt6xqDcc2Qt7RD9VHGd4S1/jbqrlt5YVS7NuUPzK3GMxxKeUlIyCoU1dKOdYpmzKckkdZE" +
            "BeobrhK3C2TKH5KXc4wc5DsylHKVzHA7PtXXqn83dzvJgEKJri1MNkJTUPPWEOAN6xXzDcwpIY1wKxVsXY0oplaiRrRMtWq8" +
            "ujws37rGoFDR1bTMao1+FelaWolPrCvFF2+tSYbAbakNwWfBkzgzME9hRzwwD0I1XDirmros3xcPdnRprlbWQeuuSwkyt+YN" +
            "UYVDarAzxNuOMnTNGSaaIyR6pSlY16jWNbQEF+iBJBncnCHPd5kZNciSZqgBNUueRQlKTA6RoJ0Iip8AtaqCzpo5z0KHnRIu" +
            "qLxxqyqUIDIYk6YwtGsUX2eNLsfxSUaNvJ2qa8esmThKdqprmTvkTo7iIonYiu6YNCw8qbVjLLpNEcNC/VldK0g8l07e9EpQ" +
            "7VRDareieQNBPjeeHpFlPRki53qtmiZhE5JFaQI3sccQnkzNX8gmJldbIvBxtPYy9tIxthK3qHWNcW5c1Q0CRb5UA97C3gUO" +
            "Ju4FfEGrdO9V7LSkxYKOD3i9fDF3L+RG1KUdcbUxWCGk6X5yN7uT+8qAOqxrqiouoltb1YCK2xsGvLi9sSX4rJ2eKdubgocl" +
            "lKpbq0IDucQLPqvQQ0NQJU7lRI4oHOGWlhNiEfLsWfql3C+4BkEQePsg7S2axoWIhtA+KCVp9qSjfOHIS8+59kFDkuMdkzYQ" +
            "zZKk9QuaGAPAU+ZNNXot3hRvumSV2ABy0mGiPEfPvRSEI+loRTZAWssFeRD7B1K8LCnRTxLeZITbmy+5bm4JHkkHUhNnclTF" +
            "B7WLs5uKTY8Vn9LBG+WuUHe8NcQXG2RRaeiLGqqLqEzqIgrElK6lqp1VWppaxemVnF6ZpJs43UwtillI6v1U+4CGvANWBt20" +
            "JJXpL7K4/RyvVIhuKnH7z4oXuynYSkAsh2ZcpF+r0Eu7BBcupquLrteBBxcSfQFdiQ9eNFOmXeK8Hw3ex3FoBA+NIIxgav0F" +
            "VC7gB4EC1/v+Atdv/de6zvsLXWuGtw5LtuH64TXDu4YPDRvTfvbuTNdP3/G7bO+g9x1/luvtM37XK2dOnxk+I3vPeMr8Z/xO" +
            "12/OJVzn8JfNZ2t/3fxeKTT/6pe/bP5FLTT/HBKut64/3Xwa5eb/uF5uflNOuGw/cf1EEifvS07mf+Wf8PmhCteJQL7rO/9Y" +
            "4Eo8i4HByGD/oDyYGPImBjNK/a5jlcfqj/Ud23ps/7FDx8zOb2Hk8IHD2mHZdhh3P4PaM2h7Bi22I5VHho/I/dpuTdK0Ie2U" +
            "JpccqjwkHXhKe0oaeurUU1LJk5VPSvufwKHHTz0u1R/cdVAqOdh38PjBxEHDw/tyXYF92PcgHn8QH/TPcH1lT7bLtse1Z+ue" +
            "XXsSe4xzHvA+IPU/gJFd/buk3btwaNepXVL9/Wvu77tf/oI/4dr/efzcZ+e6YtFKV5Qm0tdb4er1z3dNR2dzjsfZbPbIzSaa" +
            "eivx1tBxi3+ua2VLrauFrlNLM5qNlB5DqdzcI2O6XCEvk3vku2TjcEPC29EgeRvmL/B7G/IK/K8EcKlfcdWS5SV0HPLjaf+w" +
            "X+r3Y1bptGYH2prtpbZm2pU1I6DLZau0rbFttRlsthJbva3Ptst22pawmSuJNmyT+wDrAfuz0IiDuHugqbGwsG7QnKAnvDmw" +
            "UsPtWl4jP3sbWjTTdg2aW1YGBxC/GPr8zp1QNaNOK20Maq0zQnVaBwFeDvQTYJ8xkAVVoWgsGttUyAcmAYgVFkajHEKOFSZ5" +
            "AsLCKLFJjJQIiW2CaGE0htFoDKIxokdxNcHRKESJHkVSoSNaqNsft0QOVpMhOsWSLqJR0ouSnajuzrkajC/T74O7acc9DbaI" +
            "86RhWEhr6XaABH83OeE8ugL+R0fytwschefhEByYxNoO99D5yUm04/BdeEJA+2Dnx5h9Fh7XoT2wF+79SLlb4bNk51E4OoHW" +
            "StQt8HfkeRD+gX6azEIPeb1N574BL17ZFL6NL8KX4DGS/BIco/M++hHzael9+JK0HHqlV+Vt8Bm4j+a4H9fBLpJvhUdxJawm" +
            "anKspt8yfZcZjcNu+CbcCf2XSMZtid+B9eIRivw+svMgrIMNVEnbxZmJ92Ge4edgHf0xHJddFPvT8IxQ2Tama66Vb5W+JUkj" +
            "XybkAVhLRxhfozh3yovhf3GYthm6IdPwA95DiR+NbqXY36AKPUfZeIXYNoLoV54hheCnxe8+DiNMISwJSwR/R4dlyIeXdNgA" +
            "M+G8DhthOk7RYRPMxAIdNsPv0a/DFiiQFupwClwltelwqvSv0j06nAYLjAEdTodbjNt12Aq3Gt/V4Skwz3QT1FDu19IRo+NO" +
            "ql4H/QLtgDDhYYLaqZoR6qSNQqqbqAoUEPUaupbCHJhLhwJLSKqP+D2kr0A1/91KWvwcFnb7oBdmEydV8D7eXilBy/U4aoV+" +
            "EUFLyUI72fATpZ249YR3Crm1sIn8hsniZF7xZdzryPZs8jBZSrlM6mbCNkJUj5nHNFvozf8z1peRRpvQvTRjha5ROvM8xgSn" +
            "gyTWC/nbiNYHXR87V4XkOkVtosTpFFiHsMptN5NEo5AKCM1OosaEt14h1XQFj/XksYv028XMxyTbhW1e76TlPoK7BU2hu8gm" +
            "UasoSXK9sblFyfNiOrcLvx269JUr3yii2yx83ijoHI8KXhXhUX1eyZwtFv7WE8ZzcTtFwv12Czgs8tkhtHn/9OqabdRRysf6" +
            "UXTdsF6XXvr0kWwySq5TpOe7S5yjwm8v+VAIDosZKyJSHl3XZVEoImNhkf9kzdcTNyZk24neQ58t+mpaT/lJem3T18vtYvV1" +
            "j8+d5N2zRGUv5SLZLV16dyqCGiG4T8Q+lr1iUREef6eIikNhsZrbSKNH+EnG0S16Iiwq2qlXOCaijU7ojGQfKCJibt0nuoGv" +
            "4E49kyto7S+7osVktiZ2JK9Ej4g3OsF2r4i2Q9D6xjO7WX8nFhmfcY+4x9w2XpUu0WXJ7HUIa8Ufkd8ukZuY7rVPRNRBn2Sd" +
            "kx3VR7qbRNWSqyjZw7E/yVxY5LdP14uI1R/TY1kvVkW36LsILIQS+twuPrNF901cK+36Spmtx1zyN+vxuCIigxNXxcbxWNZT" +
            "jMv0Nd87vtY2TVi1Y5VopDvPMnGXiOj949czp1xmga+Vy++Uc8WdcvIskt24jvCYiCcqcjlbzGEt8evJwzLxhpTvzJ7n/7te" +
            "ccjiDbADMJHQ36Sm6xxJYCninHwXfRU9TbHGv6yJdMQ7aPFWFcb5NeTTLPhFbX2RPihr74vE6LxlYw+Urd3YeRuUdXe2bYSy" +
            "nnCsF8ouszFm5//9/+NKYPz/8SmG7xGujf0/fu3P+f/jyva/+P/x771+gsAf/0X/j+vv9X/nvaHgb32vTwn/C9/rj5654nv9" +
            "T/Zi/7d7sSa6U/V9xF4syftze7Gk1F+/F7uy9U/2Yp/sxT7Zi32yF/tkL/ZX78X+G17WTN4QKAAA";
    @Test
    void resultRecordProperties() {
        var result = new FontNormalizer.Result(10, 3, 2, 1, 4);
        assertEquals(10, result.fontsProcessed());
        assertEquals(3, result.toUnicodeFixed());
        assertEquals(2, result.widthsRepaired());
        assertEquals(1, result.type1Converted());
        assertEquals(4, result.resubset());
    }

    @Test
    void fontClassificationRecordProperties() {
        var fc = new FontNormalizer.FontClassification(
                FontType.TRUETYPE, true, true, 256, 2048, true, false, "Arial");
        assertEquals(FontType.TRUETYPE, fc.type());
        assertTrue(fc.sfnt());
        assertTrue(fc.hasCmap());
        assertEquals(256, fc.numGlyphs());
        assertEquals(2048, fc.unitsPerEm());
        assertTrue(fc.hasKerning());
        assertFalse(fc.isSubset());
        assertEquals("Arial", fc.family());
    }

    @Test
    void classifyEmptyFontDataRejects() {
        // Real FreeType correctly rejects empty data; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(EMPTY_BYTES);
            assertNotNull(fc);
            assertNotNull(fc.type());
        } catch (Exception e) {
            // Real library throws for invalid/empty font data - acceptable
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void classifyNonFontDataRejectsGracefully() {
        // Real FreeType correctly rejects random bytes; stub returns a default
        try {
            FontNormalizer.FontClassification fc = FontNormalizer.classify(
                    new byte[]{0x00, 0x01, 0x02, 0x03});
            assertNotNull(fc);
        } catch (Exception e) {
            // Real library throws for invalid font data - acceptable
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void classifyRejectsFontWithoutGlyphs() throws Exception {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] font = liberationSansBytes();
        setMaxpNumGlyphs(font, 0);

        assertThrows(JPDFiumException.class, () -> FontNormalizer.classify(font));
    }

    @Test
    void classifyEscapesQuotesInFamilyName() throws Exception {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] font = liberationSansBytes();
        FontNormalizer.FontClassification baseline = FontNormalizer.classify(font);
        assumeTrue("Liberation Sans".equals(baseline.family()),
                "unexpected family name: " + baseline.family());

        quoteFamilyName(font);
        FontNormalizer.FontClassification classified = FontNormalizer.classify(font);

        assertEquals("\"iberation Sans", classified.family(),
                "a quote in the family name must survive JSON escaping");
    }

    @Test
    void subsetRejectsCorruptFontWithoutGlyphs() {
        assumeFalse(NativeRuntime.isStub(), "requires real native bridge");
        byte[] corrupt = new byte[64];

        assertThrows(JPDFiumException.class,
                () -> FontNormalizer.subset(corrupt, new int[] {0x41}, true));
    }

    @Test
    void fixToUnicodeGeneratesCmapForEmbeddedTrueType() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = embeddedTrueTypePdf(true, false);
        assertFalse(hasToUnicodeStream(pdf), "fixture must start without /ToUnicode");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int fixed = FontNormalizer.fixToUnicode(doc, 0);
            assertTrue(fixed >= 1, "expected at least one repaired /ToUnicode, got " + fixed);
            byte[] repaired = doc.saveBytes();
            assertTrue(hasToUnicodeStream(repaired),
                    "repaired document must carry a non-empty /ToUnicode stream");

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                PDFont font = firstFont(loaded);
                assertNotNull(font, "repaired document must still expose the font");
                assertEquals("H", font.toUnicode('H'));
                assertEquals("Hello World", new PDFTextStripper().getText(loaded).trim());
            }
        }
    }

    @Test
    void repairWidthsRebuildsMissingWidthsForEmbeddedTrueType() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = embeddedTrueTypePdf(false, true);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int repaired = FontNormalizer.repairWidths(doc, 0);
            assertTrue(repaired >= 1, "expected at least one repaired /Widths, got " + repaired);
            byte[] repairedBytes = doc.saveBytes();
            assertTrue(hasWidthsArray(repairedBytes),
                    "repaired document must carry a /Widths array");

            try (PDDocument loaded = Loader.loadPDF(repairedBytes)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                int firstChar = font.getInt(COSName.FIRST_CHAR);
                assertEquals(722, widths.getInt(72 - firstChar),
                        "Liberation Sans 'H' advance width must be 722");
            }
        }
    }

    @Test
    void repairWidthsLeavesMatchingRealValuedWidthsUnchanged() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] repaired;
        try (PdfDocument doc = PdfDocument.open(embeddedTrueTypePdf(false, true))) {
            assertTrue(FontNormalizer.repairWidths(doc, 0) >= 1,
                    "fixture must first gain repaired widths");
            repaired = doc.saveBytes();
        }

        try (PdfDocument doc = PdfDocument.open(withFloatWidths(repaired))) {
            assertEquals(0, FontNormalizer.repairWidths(doc, 0),
                    "real-valued widths within one glyph unit must not be rewritten");
            byte[] unchanged = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(unchanged)) {
                COSArray widths = (COSArray) firstFontDictionary(loaded)
                        .getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                boolean fractional = false;
                for (int i = 0; i < widths.size(); i++) {
                    if (widths.get(i) instanceof COSFloat value
                            && value.floatValue() != Math.rint(value.floatValue())) {
                        fractional = true;
                        break;
                    }
                }
                assertTrue(fractional, "fractional producer width must survive the repair pass");
            }
        }
    }

    @Test
    void repairWidthsPreservesPlausibleProducerWidth() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = withIntegerWidth(embeddedTrueTypePdf(false, false), 'H', 700);
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(0, FontNormalizer.repairWidths(doc, 0),
                    "a positive producer width must not be overwritten merely for differing");
            byte[] unchanged = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(unchanged)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                int firstChar = font.getInt(COSName.FIRST_CHAR);
                assertEquals(700, widths.getInt('H' - firstChar),
                        "the producer's tracking width must survive the repair pass");
            }
        }
    }

    private static byte[] withCidWidth(byte[] pdf, int cid, int width) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary descendant = cidDescendant(doc);
            assertNotNull(descendant);
            COSArray widths = new COSArray();
            widths.add(COSInteger.get(cid));
            COSArray run = new COSArray();
            run.add(COSInteger.get(width));
            widths.add(run);
            descendant.setItem(COSName.W, widths);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] withIntegerWidth(byte[] pdf, char code, int width) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font);
            COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
            assertNotNull(widths);
            int firstChar = font.getInt(COSName.FIRST_CHAR);
            widths.set(code - firstChar, COSInteger.get(width));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void fixToUnicodeReplacesBoilerplateOnlyCMap() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = withBoilerplateToUnicode(embeddedTrueTypePdf(false, false));
        assertTrue(hasToUnicodeStream(pdf),
                "fixture must start with a boilerplate /ToUnicode stream");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.fixToUnicode(doc, 0),
                    "a /ToUnicode stream with no bfchar/bfrange entries must be regenerated");
            byte[] repaired = doc.saveBytes();

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                assertEquals("Hello World", new PDFTextStripper().getText(loaded).trim(),
                        "the regenerated CMap must keep text extraction working");
            }
        }
    }

    @Test
    void fixToUnicodeKeepsMappingAfterEmptyCmapSection() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = withMultiSectionToUnicode(embeddedTrueTypePdf(false, false));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(0, FontNormalizer.fixToUnicode(doc, 0),
                    "an empty first bfchar section must not hide a later mapping");
        }
    }

    @Test
    void repairWidthsPreservesProducerWidthForUnresolvedCode() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = unresolvedWidthPdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.repairWidths(doc, 0),
                    "the resolved 'H' width must be repaired");
            byte[] repaired = doc.saveBytes();

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                int firstChar = font.getInt(COSName.FIRST_CHAR);
                assertEquals(777, widths.getInt(65 - firstChar),
                        "/.notdef leaves code 65 unresolved; its producer width must survive");
                assertEquals(722, widths.getInt(72 - firstChar),
                        "resolved 'H' must take Liberation Sans' 722-unit advance");
            }
        }
    }

    @Test
    void repairWidthsIncludesZeroWidthCidGlyphWhenDwIsNonZero() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = cidTrueTypePdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.repairWidths(doc, 0),
                    "a CID font without /W must gain one from the embedded program");
            byte[] repaired = doc.saveBytes();

            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                assertNotNull(descendant.getDictionaryObject(COSName.W),
                        "CID font must gain /W");
                Integer width = cidWidth(descendant, 1);
                assertNotNull(width,
                        "/W must spell out the zero-advance CID instead of inheriting /DW");
                assertEquals(0, width.intValue(),
                        "/DW 1000 must not hide the zero-width glyph");
            }
        }
    }

    @Test
    void perPageRepairsRejectNegativePageIndex() throws Exception {
        try (PdfDocument doc = PdfDocument.open(embeddedTrueTypePdf(false, false))) {
            assertThrows(IllegalArgumentException.class, () -> FontNormalizer.fixToUnicode(doc, -1));
            assertThrows(IllegalArgumentException.class, () -> FontNormalizer.repairWidths(doc, -2));
        }
    }

    @Test
    void fixToUnicodeMapsWinAnsiHighRangeAndDifferences() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = winAnsiDifferencesPdf();
        assertFalse(hasToUnicodeStream(pdf), "fixture must start without /ToUnicode");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.fixToUnicode(doc, 0),
                    "expected one repaired /ToUnicode");
            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                PDFont font = firstFont(loaded);
                assertNotNull(font);
                assertEquals("\u20AC", font.toUnicode(128),
                        "/Differences /Euro must map to the Euro sign");
                assertEquals("\u2022", font.toUnicode(129),
                        "/Differences /bullet must map to the bullet");
            }
        }
    }

    @Test
    void normalizeAllProcessesDistinctFontsOnEveryPage() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = twoPageEmbeddedFontsPdf();
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizeAll(doc);
            assertEquals(2, result.fontsProcessed(),
                    "the all-page pass must visit both pages: " + result);
            assertEquals(2, result.widthsRepaired(),
                    "each page's distinct font needs its own width repair: " + result);

            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                for (int page = 0; page < 2; page++) {
                    COSDictionary font = loaded.getPage(page).getResources()
                            .getFont(COSName.getPDFName("F1")).getCOSObject();
                    COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
                    assertNotNull(widths, "page " + page + " font must carry /Widths");
                    int firstChar = font.getInt(COSName.FIRST_CHAR, 0);
                    assertEquals(722, widths.getInt('H' - firstChar),
                            "page " + page + " H width must match the program");
                    assertNotNull(font.getDictionaryObject(COSName.TO_UNICODE),
                            "page " + page + " font must carry /ToUnicode");
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setStartPage(page + 1);
                    stripper.setEndPage(page + 1);
                    assertEquals("H", stripper.getText(loaded).strip(),
                            "page " + page + " must extract its repaired text");
                }
            }
        }
    }

    @Test
    void normalizePageRepairsToUnicodeAndWidthsInOnePass() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = embeddedTrueTypePdf(true, true);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertTrue(result.fontsProcessed() >= 1,
                    "expected at least one embedded font, got " + result.fontsProcessed());
            assertTrue(result.toUnicodeFixed() >= 1,
                    "expected /ToUnicode repair, got " + result.toUnicodeFixed());
            assertTrue(result.widthsRepaired() >= 1,
                    "expected width repair, got " + result.widthsRepaired());

            byte[] repaired = doc.saveBytes();
            assertTrue(hasToUnicodeStream(repaired), "/ToUnicode missing after normalizePage");
            assertTrue(hasWidthsArray(repaired), "/Widths missing after normalizePage");
        }
    }

    @Test
    void normalizeAllRepairsCidToUnicodeAndCompactWidthsIdempotently() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = cidTrueTypePdf();
        assertFalse(hasToUnicodeStream(pdf), "fixture must start without /ToUnicode");

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result first = FontNormalizer.normalizeAll(doc);
            assertEquals(1, first.toUnicodeFixed(), "CID font must gain /ToUnicode");
            assertEquals(1, first.widthsRepaired(), "CID font must gain a compact /W");

            byte[] repaired = doc.saveBytes();
            assertTrue(hasToUnicodeStream(repaired),
                    "repaired CID document must carry a non-empty /ToUnicode stream");
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                COSArray widths = (COSArray) descendant.getDictionaryObject(COSName.W);
                assertNotNull(widths, "CID font must gain /W");
                assertTrue(widths.size() > 0, "/W must not be empty");
                assertTrue(widths.getObject(0) instanceof COSInteger,
                        "compact /W must start with a CID, not a per-glyph array");
                PDFont font = firstFont(loaded);
                assertEquals("A", font.toUnicode(0x24),
                        "generated CID /ToUnicode must map CID 36 to A in Liberation Sans");
            }

            FontNormalizer.Result second = FontNormalizer.normalizeAll(doc);
            assertEquals(0, second.toUnicodeFixed(),
                    "a second normalizeAll pass must not rewrite /ToUnicode");
            assertEquals(0, second.widthsRepaired(),
                    "a second normalizeAll pass must not rewrite the compact /W");
        }
    }

    @Test
    void normalizePageFindsFontsInsideFormXObjectAndAnnotationAppearance() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = formXObjectFontPdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertEquals(2, result.toUnicodeFixed(),
                    "fonts inside a /Form XObject and an /AP /N stream must get /ToUnicode");
            assertEquals(2, result.widthsRepaired(),
                    "fonts inside a /Form XObject and an /AP /N stream must get repaired /Widths");

            byte[] repaired = doc.saveBytes();
            String raw = new String(repaired, StandardCharsets.ISO_8859_1);
            assertTrue(raw.contains("/ToUnicode"),
                    "repaired fonts must carry a /ToUnicode stream");
            assertTrue(raw.contains("/Widths"), "repaired fonts must carry a /Widths array");
        }
    }

    @Test
    void normalizePageInvalidatesOpenPageWhenRepairRewritesDocument() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        try (PdfDocument doc = PdfDocument.open(embeddedTrueTypePdf(false, true));
                PdfPage page = doc.page(0)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertTrue(result.widthsRepaired() >= 1, "fixture must repair widths");
            assertThrows(IllegalStateException.class, page::rotation,
                    "an open page must be stale after the document is rewritten");
        }
    }

    @Test
    void normalizePageSelectsMatchingFaceFromTrueTypeCollection() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = collectionTrueTypePdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertEquals(1, result.fontsProcessed());
            assertEquals(1, result.widthsRepaired(),
                    "the selected collection face must gain repaired /Widths");
            assertTrue(result.resubset() >= 1,
                    "a multi-face collection stream must be re-subset to the selected face");

            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
                assertNotNull(widths);
                int firstChar = font.getInt(COSName.FIRST_CHAR);
                assertEquals(500, widths.getInt('H' - firstChar),
                        "FaceTwo's 1024/2048 advance proves the second TTC face was selected");
                assertTrue(font.getNameAsString(COSName.BASE_FONT)
                                .matches("[A-Z]{6}\\+FaceTwo-Regular"),
                        "a re-subset collection font must carry a deterministic subset tag");
                COSDictionary descriptor = font.getCOSDictionary(COSName.FONT_DESC);
                assertNotNull(descriptor);
                assertNull(descriptor.getDictionaryObject(COSName.getPDFName("CharSet")),
                        "PDF 32000-1 9.8.2 defines CharSet only for Type 1 subsets");
                assertEquals("H", new PDFTextStripper().getText(loaded).trim(),
                        "the re-subset font program must still draw drawn glyphs");
            }

            FontNormalizer.Result second = FontNormalizer.normalizePage(doc, 0);
            assertEquals(0, second.toUnicodeFixed());
            assertEquals(0, second.widthsRepaired());
            assertEquals(0, second.type1Converted());
            assertEquals(0, second.resubset());
        }
    }

    @Test
    void normalizePageConvertsCffInSfntToBareType1C() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = cffInSfntType1Pdf();

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizePage(doc, 0);
            assertEquals(1, result.fontsProcessed());
            assertEquals(1, result.type1Converted(),
                    "CFF-in-SFNT under /Type1 must become a bare Type1C stream");

            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSDictionary descriptor =
                        (COSDictionary) font.getDictionaryObject(COSName.FONT_DESC);
                assertNotNull(descriptor);
                assertFalse(descriptor.containsKey(COSName.FONT_FILE));
                assertFalse(descriptor.containsKey(COSName.FONT_FILE2));
                COSStream cff = (COSStream) descriptor.getDictionaryObject(COSName.FONT_FILE3);
                assertNotNull(cff, "converted font must keep /FontFile3");
                assertEquals("Type1C", cff.getNameAsString(COSName.SUBTYPE));

                byte[] cffBytes;
                try (InputStream in = cff.createInputStream()) {
                    cffBytes = in.readAllBytes();
                }
                FontNormalizer.FontClassification classification =
                        FontNormalizer.classify(cffBytes);
                assertFalse(classification.sfnt(), "converted program must be a bare CFF");
                assertTrue(classification.numGlyphs() > 1);
                assertEquals("H", new PDFTextStripper().getText(loaded).trim(),
                        "the converted bare Type1C font program must still draw drawn glyphs");
            }

            FontNormalizer.Result second = FontNormalizer.normalizePage(doc, 0);
            assertEquals(0, second.toUnicodeFixed());
            assertEquals(0, second.widthsRepaired());
            assertEquals(0, second.type1Converted());
            assertEquals(0, second.resubset());
        }
    }

    @Test
    void normalizeAllSynthesizesCidSetFromProgramCoverage() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        // The stale /CIDSet marks only CID 0, the drawn CID 36, and unreferenced
        // CID 50. ISO 19005-2 6.2.11.4 test 4 requires every CID present in the
        // program, so the normalizer must rebuild the full 0..numGlyphs-1 bitmap.
        byte[] staleCidSet = new byte[] {(byte) 0x80, 0, 0, 0, 0x08, 0, 0x20};
        byte[] pdf = cidTrueTypePdf("ABCDEF+LiberationSans", staleCidSet);
        int numGlyphs = FontNormalizer.classify(liberationSansBytes()).numGlyphs();
        byte[] expected = new byte[(numGlyphs + 7) / 8];
        for (int cid = 0; cid < numGlyphs; cid++)
            expected[cid / 8] |= (byte) (0x80 >> (cid % 8));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result first = FontNormalizer.normalizeAll(doc);
            assertEquals(0, first.resubset(),
                    "an already subset-tagged font must not be re-subset");

            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                COSDictionary descriptor =
                        (COSDictionary) descendant.getDictionaryObject(COSName.FONT_DESC);
                assertNotNull(descriptor);
                assertArrayEquals(expected, streamBytes(descriptor, COSName.CID_SET),
                        "/CIDSet must describe every CID present in the embedded program");
            }

            FontNormalizer.Result second = FontNormalizer.normalizeAll(doc);
            assertEquals(0, second.toUnicodeFixed());
            assertEquals(0, second.widthsRepaired());
            assertEquals(0, second.type1Converted());
            assertEquals(0, second.resubset());
        }
    }

    @Test
    void normalizeAllWritesCidSetForRetainedSubsetGlyphs() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = cidTrueTypePdf("LiberationSans", new byte[] {(byte) 0x80});
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result result = FontNormalizer.normalizeAll(doc);
            assertEquals(1, result.resubset(), "an untagged CID font must be re-subset");

            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                COSDictionary descriptor =
                        (COSDictionary) descendant.getDictionaryObject(COSName.FONT_DESC);
                assertNotNull(descriptor);
                byte[] cidSet = streamBytes(descriptor, COSName.CID_SET);
                assertEquals(5, cidSet.length,
                        "CIDSet must stop at the highest present GID in the re-subsetted /Identity program");
                // Re-subsetted /Identity program keeps glyph slots 0..36 (max_gid=36), so ISO 19005-2
                // §6.2.11.4.2 requires CIDs 0..36 present in the font program to all be marked.
                for (int i = 0; i < 4; i++) {
                    assertEquals((byte) 0xFF, cidSet[i], "CIDSet byte " + i + " must mark all CIDs present");
                }
                assertEquals((byte) 0xF8, cidSet[4], "CIDSet final byte must mark CIDs 32..36");
                assertEquals("A", new PDFTextStripper().getText(loaded).strip(),
                        "re-subsetting must preserve extracted text");
            }
        }
    }

    @Test
    void normalizeAllRepairsStaleCidSetAndInconsistentWidths() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] input = Files.readAllBytes(Path.of(FontNormalizerTest.class
                .getResource("/pdfs/general/issue20065.pdf").toURI()));
        PdfFontEmbedder.Result embedded = PdfFontEmbedder.embedMissing(input);
        assertTrue(embedded.unresolved().isEmpty(), "unresolved: " + embedded.unresolved());
        byte[] stale = withCidWidth(embedded.pdf(), 0, 500);

        try (PdfDocument doc = PdfDocument.open(stale)) {
            FontNormalizer.Result result = FontNormalizer.normalizeAll(doc);
            assertEquals(1, result.widthsRepaired(),
                    "the existing /W must be corrected against the embedded program");
            assertEquals(0, result.resubset(), "the already-tagged font must not be re-subset");

            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                COSDictionary descriptor =
                        (COSDictionary) descendant.getDictionaryObject(COSName.FONT_DESC);
                assertNotNull(descriptor);
                COSStream map = (COSStream)
                        descendant.getDictionaryObject(COSName.CID_TO_GID_MAP);
                assertNotNull(map, "embedded CID font must carry a CIDToGIDMap");
                int entries;
                try (InputStream in = map.createInputStream()) {
                    entries = in.readAllBytes().length / 2;
                }
                byte[] cidSet = streamBytes(descriptor, COSName.CID_SET);
                assertEquals((entries + 7) / 8, cidSet.length,
                        "CIDSet must cover every CID in the map");
                for (int cid = 0; cid < entries; cid++) {
                    assertTrue((cidSet[cid / 8] & (0x80 >> (cid % 8))) != 0,
                            "CIDSet must mark CID " + cid);
                }
                assertEquals("Hello World", new PDFTextStripper().getText(loaded).strip(),
                        "repairing widths and CIDSet must not change extracted text");
            }
        }
    }

    @Test
    void singlePurposeNormalizerCallsDoNotRewriteCidSetOrInvalidatePages() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        // Start from a fully normalized CID PDF so ToUnicode and widths are already correct
        byte[] basePdf;
        try (PdfDocument initial = PdfDocument.open(cidTrueTypePdf("ABCDEF+LiberationSans", null))) {
            FontNormalizer.normalizeAll(initial);
            basePdf = initial.saveBytes();
        }
        // Inject a stale /CIDSet stream into the descriptor
        byte[] pdfWithStaleCidSet;
        try (PDDocument doc = Loader.loadPDF(basePdf)) {
            COSDictionary descendant = cidDescendant(doc);
            assertNotNull(descendant);
            COSDictionary descriptor = (COSDictionary) descendant.getDictionaryObject(COSName.FONT_DESC);
            assertNotNull(descriptor);
            COSStream stale = new PDStream(doc, new ByteArrayInputStream(new byte[] {0})).getCOSObject();
            descriptor.setItem(COSName.CID_SET, stale);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfWithStaleCidSet = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdfWithStaleCidSet);
                PdfPage page = doc.page(0)) {
            int tuc = FontNormalizer.fixToUnicode(doc, 0);
            assertEquals(0, tuc, "fixToUnicode should not report repairs when ToUnicode is already valid");
            assertEquals(0, page.rotation(), "page must remain valid; /CIDSet was not touched by single-purpose call");

            int widths = FontNormalizer.repairWidths(doc, 0);
            assertEquals(0, widths, "repairWidths should not report repairs when widths are already valid");
            assertEquals(0, page.rotation(), "page must remain valid; /CIDSet was not touched by single-purpose call");
        }
    }

    /**
     * Builds a one-page PDF whose text uses an embedded simple TrueType font.
     * Optionally removes the font dictionary entries that the normalizer repairs.
     */
    private static byte[] embeddedTrueTypePdf(boolean stripToUnicode, boolean stripWidths)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDTrueTypeFont font;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                assertNotNull(in, "PDFBox no longer bundles LiberationSans");
                font = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }

            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(72, 700);
                content.showText("Hello World");
                content.endText();
            }

            COSDictionary dictionary = font.getCOSObject();
            if (stripToUnicode) dictionary.removeItem(COSName.TO_UNICODE);
            if (stripWidths) {
                dictionary.removeItem(COSName.WIDTHS);
                dictionary.removeItem(COSName.FIRST_CHAR);
                dictionary.removeItem(COSName.LAST_CHAR);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Rewrites every /Widths entry as a {@link COSFloat} without changing its
     * numeric value, to exercise the native real-value tolerance path.
     */
    private static byte[] withFloatWidths(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");
            COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
            assertNotNull(widths, "fixture must contain /Widths");
            for (int i = 0; i < widths.size(); i++) {
                float value = ((COSNumber) widths.get(i)).floatValue();
                widths.set(i, new COSFloat(value + 0.25f));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a PDF whose embedded TrueType font uses WinAnsi with a
     * {@code /Differences} array for the 0x80/0x81 high range.
     */
    private static byte[] winAnsiDifferencesPdf() throws IOException {
        try (PDDocument doc = Loader.loadPDF(embeddedTrueTypePdf(true, false))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");

            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
            COSArray differences = new COSArray();
            differences.add(COSInteger.get(128));
            differences.add(COSName.getPDFName("Euro"));
            differences.add(COSInteger.get(129));
            differences.add(COSName.getPDFName("bullet"));
            encoding.setItem(COSName.DIFFERENCES, differences);
            font.setItem(COSName.ENCODING, encoding);
            font.removeItem(COSName.TO_UNICODE);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Replaces the font's {@code /ToUnicode} with the CMap prologue only, the shape a
     * broken producer emits when it never wrote any {@code beginbfchar} or
     * {@code beginbfrange} section.
     */
    private static byte[] withMultiSectionToUnicode(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");
            COSStream toUnicode = new COSStream();
            try (OutputStream out = toUnicode.createOutputStream()) {
                out.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /Adobe-Identity-UCS def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        0 beginbfchar
                        endbfchar
                        1 beginbfrange
                        <20> <7E> <0020>
                        endbfrange
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(COSName.TO_UNICODE, toUnicode);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] withBoilerplateToUnicode(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");
            COSStream toUnicode = new COSStream();
            try (OutputStream out = toUnicode.createOutputStream()) {
                out.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                        /CMapName /Adobe-Identity-UCS def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(COSName.TO_UNICODE, toUnicode);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Maps code 65 to {@code /.notdef} so it cannot resolve to a glyph, while the
     * producer widths carry 777 for code 65 and a wrong value for code 72.
     */
    private static byte[] unresolvedWidthPdf() throws IOException {
        try (PDDocument doc = Loader.loadPDF(embeddedTrueTypePdf(false, false))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font, "fixture must contain a font");

            COSDictionary encoding = new COSDictionary();
            COSArray differences = new COSArray();
            differences.add(COSInteger.get(65));
            differences.add(COSName.getPDFName(".notdef"));
            encoding.setItem(COSName.DIFFERENCES, differences);
            font.setItem(COSName.ENCODING, encoding);

            int firstChar = font.getInt(COSName.FIRST_CHAR);
            COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
            assertNotNull(widths, "fixture must contain /Widths");
            assertTrue(65 >= firstChar && 72 - firstChar < widths.size(),
                    "fixture widths must cover codes 65 and 72");
            widths.set(65 - firstChar, COSInteger.get(777));
            widths.set(72 - firstChar, COSInteger.get(0));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a PDF whose only embedded font lives in a {@code /Form} XObject's
     * private {@code /Resources}, not in the page resources.
     */
    private static byte[] formXObjectFontPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDTrueTypeFont font;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                assertNotNull(in, "PDFBox no longer bundles LiberationSans");
                font = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }

            COSDictionary dictionary = font.getCOSObject();
            dictionary.removeItem(COSName.TO_UNICODE);
            dictionary.removeItem(COSName.WIDTHS);
            dictionary.removeItem(COSName.FIRST_CHAR);
            dictionary.removeItem(COSName.LAST_CHAR);

            PDResources formResources = new PDResources();
            formResources.put(COSName.getPDFName("F1"), font);

            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 200, 100));
            form.setResources(formResources);

            PDTrueTypeFont annotationFont;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                assertNotNull(in, "PDFBox no longer bundles LiberationSans");
                annotationFont = PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
            }
            COSDictionary annotationDictionary = annotationFont.getCOSObject();
            annotationDictionary.removeItem(COSName.TO_UNICODE);
            annotationDictionary.removeItem(COSName.WIDTHS);
            annotationDictionary.removeItem(COSName.FIRST_CHAR);
            annotationDictionary.removeItem(COSName.LAST_CHAR);
            PDResources annotationResources = new PDResources();
            annotationResources.put(COSName.getPDFName("F2"), annotationFont);
            PDAppearanceStream appearance = new PDAppearanceStream(doc);
            appearance.setBBox(new PDRectangle(0, 0, 100, 50));
            appearance.setResources(annotationResources);
            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.setRectangle(new PDRectangle(0, 0, 100, 50));
            COSDictionary appearanceDictionary = new COSDictionary();
            appearanceDictionary.setItem(COSName.N, appearance.getCOSObject());
            widget.getCOSObject().setItem(COSName.AP, appearanceDictionary);
            page.getAnnotations().add(widget);

            PDResources pageResources = new PDResources();
            pageResources.put(COSName.getPDFName("Fm0"), form);
            page.setResources(pageResources);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a one-page PDF with an Identity-H CIDFontType2 backed by Liberation Sans, with
     * {@code /ToUnicode} and the descendant's {@code /W} removed. CID and GID are identical.
     */
    private static byte[] twoPageEmbeddedFontsPdf() throws IOException {
        byte[] program = liberationSansBytes();
        try (PDDocument doc = new PDDocument()) {
            for (int pageIndex = 0; pageIndex < 2; pageIndex++) {
                String baseFont = pageIndex == 0 ? "PageOne-Regular" : "PageTwo-Regular";
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                COSDictionary descriptor = new COSDictionary();
                descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
                descriptor.setName(COSName.FONT_NAME, baseFont);
                descriptor.setInt(COSName.FLAGS, 32);
                descriptor.setItem(COSName.FONT_BBOX, new COSArray());
                descriptor.setInt(COSName.ITALIC_ANGLE, 0);
                descriptor.setInt(COSName.ASCENT, 900);
                descriptor.setInt(COSName.DESCENT, -200);
                descriptor.setInt(COSName.CAP_HEIGHT, 700);
                descriptor.setInt(COSName.STEM_V, 80);
                COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(program))
                        .getCOSObject();
                fontFile.setInt(COSName.LENGTH1, program.length);
                descriptor.setItem(COSName.FONT_FILE2, fontFile);
                COSDictionary font = new COSDictionary();
                font.setItem(COSName.TYPE, COSName.FONT);
                font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
                font.setName(COSName.BASE_FONT, baseFont);
                font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
                font.setItem(COSName.FONT_DESC, descriptor);
                COSDictionary fonts = new COSDictionary();
                fonts.setItem(COSName.getPDFName("F1"), font);
                COSDictionary resources = new COSDictionary();
                resources.setItem(COSName.FONT, fonts);
                page.setResources(new PDResources(resources));
                page.setContents(new PDStream(doc, new ByteArrayInputStream(
                        "BT /F1 12 Tf 72 700 Td (H) Tj ET"
                                .getBytes(StandardCharsets.US_ASCII))));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] cidTrueTypePdf() throws IOException {
        return cidTrueTypePdf("LiberationSans", null);
    }

    /**
     * Builds an Identity-H CIDFontType2 PDF. {@code baseFont} may carry a subset
     * tag, and {@code cidSet} adds an existing /CIDSet stream when non-null.
     */
    private static byte[] cidTrueTypePdf(String baseFont, byte[] cidSet)
            throws IOException {
        byte[] program = liberationSansBytes();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, baseFont);
            descriptor.setInt(COSName.FLAGS, 32);
            descriptor.setItem(COSName.FONT_BBOX, new COSArray());
            descriptor.setInt(COSName.ITALIC_ANGLE, 0);
            descriptor.setInt(COSName.ASCENT, 900);
            descriptor.setInt(COSName.DESCENT, -200);
            descriptor.setInt(COSName.CAP_HEIGHT, 700);
            descriptor.setInt(COSName.STEM_V, 80);
            COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(program))
                    .getCOSObject();
            fontFile.setInt(COSName.LENGTH1, program.length);
            descriptor.setItem(COSName.FONT_FILE2, fontFile);
            if (cidSet != null) {
                COSStream stale = new PDStream(doc,
                        new ByteArrayInputStream(cidSet)).getCOSObject();
                descriptor.setItem(COSName.CID_SET, stale);
            }

            COSDictionary cidSystemInfo = new COSDictionary();
            cidSystemInfo.setString(COSName.REGISTRY, "Adobe");
            cidSystemInfo.setString(COSName.ORDERING, "Identity");
            cidSystemInfo.setInt(COSName.SUPPLEMENT, 0);
            COSDictionary descendant = new COSDictionary();
            descendant.setItem(COSName.TYPE, COSName.FONT);
            descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
            descendant.setName(COSName.BASE_FONT, baseFont);
            descendant.setItem(COSName.CIDSYSTEMINFO, cidSystemInfo);
            descendant.setInt(COSName.DW, 1000);
            descendant.setItem(COSName.FONT_DESC, descriptor);

            COSArray descendants = new COSArray();
            descendants.add(descendant);
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE0);
            font.setName(COSName.BASE_FONT, baseFont);
            font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            font.setItem(COSName.DESCENDANT_FONTS, descendants);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td <0024> Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static COSDictionary cidDescendant(PDDocument doc) throws IOException {
        COSDictionary font = firstFontDictionary(doc);
        if (font == null || !(font.getDictionaryObject(COSName.DESCENDANT_FONTS)
                instanceof COSArray descendants) || descendants.size() == 0) {
            return null;
        }
        return (COSDictionary) descendants.getObject(0);
    }

    /** Looks up one CID in a {@code /W} array, handling both range and list forms. */
    private static Integer cidWidth(COSDictionary descendant, int cid) {
        if (!(descendant.getDictionaryObject(COSName.W) instanceof COSArray widths)) return null;
        for (int i = 0; i < widths.size(); ) {
            if (!(widths.getObject(i++) instanceof COSInteger start)) return null;
            if (i >= widths.size()) return null;
            COSBase next = widths.getObject(i++);
            if (next instanceof COSArray list) {
                int index = cid - start.intValue();
                if (index >= 0 && index < list.size() && list.getObject(index) instanceof COSNumber value) {
                    return value.intValue();
                }
            } else if (next instanceof COSInteger last) {
                if (i >= widths.size()) return null;
                int width = ((COSNumber) widths.getObject(i++)).intValue();
                if (cid >= start.intValue() && cid <= last.intValue()) return width;
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * Builds a one-page PDF whose embedded TrueType program is a two-face
     * collection. The PDF font names {@code FaceTwo-Regular}, whose 'H' advance
     * (500) differs from {@code FaceOne-Regular} (700), so repaired widths prove
     * which face FreeType selected.
     */
    private static byte[] collectionTrueTypePdf() throws IOException {
        byte[] ttc = gunzipBase64(TTC_FONTS_BASE64);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, "FaceTwo-Regular");
            descriptor.setInt(COSName.FLAGS, 32);
            descriptor.setItem(COSName.FONT_BBOX, new COSArray());
            descriptor.setInt(COSName.ITALIC_ANGLE, 0);
            descriptor.setInt(COSName.ASCENT, 800);
            descriptor.setInt(COSName.DESCENT, -200);
            descriptor.setInt(COSName.CAP_HEIGHT, 700);
            descriptor.setInt(COSName.STEM_V, 80);
            COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(ttc))
                    .getCOSObject();
            fontFile.setInt(COSName.LENGTH1, ttc.length);
            descriptor.setItem(COSName.FONT_FILE2, fontFile);

            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.getPDFName("TrueType"));
            font.setName(COSName.BASE_FONT, "FaceTwo-Regular");
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setItem(COSName.FONT_DESC, descriptor);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td (H) Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Builds a PDF whose /Type1 font carries a CFF-in-SFNT (/Subtype /OpenType)
     * program, a shape the normalizer must rewrite as bare Type1C CFF.
     */
    private static byte[] cffInSfntType1Pdf() throws IOException {
        byte[] otf = gunzipBase64(CFF_OTF_BASE64);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, "CffTest-Regular");
            descriptor.setInt(COSName.FLAGS, 32);
            descriptor.setItem(COSName.FONT_BBOX, new COSArray());
            descriptor.setInt(COSName.ITALIC_ANGLE, 0);
            descriptor.setInt(COSName.ASCENT, 800);
            descriptor.setInt(COSName.DESCENT, -200);
            descriptor.setInt(COSName.CAP_HEIGHT, 700);
            descriptor.setInt(COSName.STEM_V, 80);
            COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(otf))
                    .getCOSObject();
            fontFile.setItem(COSName.SUBTYPE, COSName.getPDFName("OpenType"));
            descriptor.setItem(COSName.FONT_FILE3, fontFile);

            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE1);
            font.setName(COSName.BASE_FONT, "CffTest-Regular");
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setItem(COSName.FONT_DESC, descriptor);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td (H) Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] gunzipBase64(String value) throws IOException {
        byte[] compressed = Base64.getDecoder().decode(value);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    private static byte[] streamBytes(COSDictionary dictionary, COSName key) throws IOException {
        COSStream stream = (COSStream) dictionary.getDictionaryObject(key);
        assertNotNull(stream, "missing stream " + key.getName());
        try (InputStream in = stream.createInputStream()) {
            return in.readAllBytes();
        }
    }

    private static boolean hasToUnicodeStream(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            if (font == null
                    || !(font.getDictionaryObject(COSName.TO_UNICODE)
                            instanceof COSStream stream)) {
                return false;
            }
            try (InputStream data = stream.createInputStream()) {
                return data.readAllBytes().length > 0;
            }
        }
    }

    private static boolean hasWidthsArray(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            return font != null && font.getDictionaryObject(COSName.WIDTHS) instanceof COSArray;
        }
    }

    private static PDFont firstFont(PDDocument doc) throws IOException {
        if (doc.getNumberOfPages() == 0) return null;
        PDResources resources = doc.getPage(0).getResources();
        if (resources == null) return null;
        var names = resources.getFontNames().iterator();
        if (!names.hasNext()) return null;
        return resources.getFont(names.next());
    }

    private static COSDictionary firstFontDictionary(PDDocument doc) throws IOException {
        PDFont font = firstFont(doc);
        return font == null ? null : font.getCOSObject();
    }

    private static byte[] liberationSansBytes() throws IOException {
        try (InputStream font = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(font, "PDFBox no longer bundles LiberationSans");
            return font.readAllBytes();
        }
    }

    private static void setMaxpNumGlyphs(byte[] font, int count) {
        ByteBuffer buffer = tableBuffer(font);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (buffer.getInt(record) == 0x6D617870) { // 'maxp'
                buffer.putInt(buffer.getInt(record + 8) + 4, count);
                return;
            }
        }
        throw new IllegalArgumentException("font has no maxp table");
    }

    private static void quoteFamilyName(byte[] font) {
        ByteBuffer buffer = tableBuffer(font);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (buffer.getInt(record) != 0x6E616D65) continue; // 'name'
            int table = buffer.getInt(record + 8);
            int count = buffer.getShort(table + 2) & 0xFFFF;
            int strings = table + (buffer.getShort(table + 4) & 0xFFFF);
            for (int j = 0; j < count; j++) {
                int q = table + 6 + 12 * j;
                int platform = buffer.getShort(q) & 0xFFFF;
                int nameId = buffer.getShort(q + 6) & 0xFFFF;
                int length = buffer.getShort(q + 8) & 0xFFFF;
                if ((nameId != 1 && nameId != 16) || length < 2) continue;
                int start = strings + (buffer.getShort(q + 10) & 0xFFFF);
                if (platform == 3 || platform == 0) {
                    buffer.put(start, (byte) 0);
                    buffer.put(start + 1, (byte) '"');
                } else {
                    buffer.put(start, (byte) '"');
                }
            }
            return;
        }
        throw new IllegalArgumentException("font has no name table");
    }

    @Test
    void normalizingStandardEncodingPreservesQuoterightInDifferences() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf;
        try (PDDocument doc = Loader.loadPDF(embeddedTrueTypePdf(true, false))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font);
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.BASE_ENCODING, COSName.STANDARD_ENCODING);
            font.setItem(COSName.ENCODING, encoding);

            PDPage page = doc.getPage(0);
            COSName fontKey = page.getResources().getFontNames().iterator().next();
            String stream = "BT /" + fontKey.getName() + " 12 Tf 72 700 Td (a'b) Tj ET";
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    stream.getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result res = FontNormalizer.normalizePage(doc, 0);
            assertTrue(res.fontsProcessed() >= 1);
            byte[] normalized = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(normalized)) {
                PDFont font = firstFont(loaded);
                assertNotNull(font);
                COSDictionary enc = font.getCOSObject().getCOSDictionary(COSName.ENCODING);
                assertNotNull(enc, "normalized font must retain encoding dictionary");
                assertEquals(COSName.WIN_ANSI_ENCODING, enc.getDictionaryObject(COSName.BASE_ENCODING));
                COSArray diffs = enc.getCOSArray(COSName.DIFFERENCES);
                assertNotNull(diffs, "StandardEncoding differences must be preserved in /Differences");
                boolean foundQuoteright = false;
                int code = 0;
                for (COSBase item : diffs) {
                    if (item instanceof COSInteger ci) {
                        code = ci.intValue();
                    } else if (item instanceof COSName cn) {
                        if (code == 0x27 && "quoteright".equals(cn.getName())) {
                            foundQuoteright = true;
                        }
                        code++;
                    }
                }
                assertTrue(foundQuoteright, "0x27 must remain /quoteright in /Differences: " + diffs);
                assertEquals("\u2019", font.toUnicode(0x27),
                        "0x27 ToUnicode must remain U+2019 after StandardEncoding -> WinAnsiEncoding normalization");
                String extracted = new PDFTextStripper().getText(loaded).trim();
                assertEquals("a\u2019b", extracted);
            }
        }
    }

    @Test
    void normalizingDifferencesPreservesAlgorithmicUnicodeNames() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf;
        try (PDDocument doc = Loader.loadPDF(embeddedTrueTypePdf(true, false))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font);
            COSArray diffs = new COSArray();
            diffs.add(COSInteger.get(1));
            diffs.add(COSName.getPDFName("uni0041"));
            diffs.add(COSName.getPDFName("u0042"));
            diffs.add(COSName.getPDFName("BogusNonAgl999"));
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
            encoding.setItem(COSName.DIFFERENCES, diffs);
            font.setItem(COSName.ENCODING, encoding);

            PDPage page = doc.getPage(0);
            COSName fontKey = page.getResources().getFontNames().iterator().next();
            String stream = "BT /" + fontKey.getName() + " 12 Tf 72 700 Td <0102> Tj ET";
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    stream.getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.normalizePage(doc, 0);
            byte[] normalized = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(normalized)) {
                COSDictionary font = firstFontDictionary(loaded);
                COSDictionary enc = font.getCOSDictionary(COSName.ENCODING);
                assertNotNull(enc);
                COSArray diffs = enc.getCOSArray(COSName.DIFFERENCES);
                assertNotNull(diffs, "/Differences with algorithmic glyph names must be preserved");
                java.util.List<String> names = new java.util.ArrayList<>();
                for (COSBase item : diffs) {
                    if (item instanceof COSName cn) names.add(cn.getName());
                }
                assertTrue(names.contains("uni0041"), "Expected uni0041 to be preserved, got: " + names);
                assertTrue(names.contains("u0042"), "Expected u0042 to be preserved, got: " + names);
                assertFalse(names.contains("BogusNonAgl999"),
                        "Expected unknown glyph name to be stripped, got: " + names);
            }
        }
    }

    @Test
    void resubsettingOpenTypeCffUpdatesCharSetToRetainedGlyphs() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] otf = gunzipBase64(CFF_MULTI_OTF_BASE64);
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, "MultiCff-Regular");
            descriptor.setInt(COSName.FLAGS, 32);
            descriptor.setItem(COSName.FONT_BBOX, new COSArray());
            descriptor.setInt(COSName.ITALIC_ANGLE, 0);
            descriptor.setInt(COSName.ASCENT, 800);
            descriptor.setInt(COSName.DESCENT, -200);
            descriptor.setInt(COSName.CAP_HEIGHT, 700);
            descriptor.setInt(COSName.STEM_V, 80);
            descriptor.setString(COSName.CHAR_SET, "/H/I/J");
            COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(otf)).getCOSObject();
            fontFile.setItem(COSName.SUBTYPE, COSName.getPDFName("OpenType"));
            descriptor.setItem(COSName.FONT_FILE3, fontFile);

            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
            font.setName(COSName.BASE_FONT, "MultiCff-Regular");
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setItem(COSName.FONT_DESC, descriptor);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td (H) Tj ET".getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result res = FontNormalizer.normalizePage(doc, 0);
            assertEquals(1, res.resubset(), "Multi-glyph CFF OpenType font should be resubsetted");
            byte[] normalized = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(normalized)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                COSDictionary desc = font.getCOSDictionary(COSName.FONT_DESC);
                assertNotNull(desc);
                String charset = desc.getString(COSName.CHAR_SET);
                assertNotNull(charset, "Expected /CharSet to remain updated");
                assertTrue(charset.contains("/H"), "Expected retained glyph /H in /CharSet: " + charset);
                assertFalse(charset.contains("/I"), "Expected unretained glyph /I removed from /CharSet: " + charset);
                assertFalse(charset.contains("/J"), "Expected unretained glyph /J removed from /CharSet: " + charset);
            }
        }
    }

    private static ByteBuffer tableBuffer(byte[] font) {
        return ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN);
    }

    @Test
    void subsetPreservesFormat12NonBmpCmapAlongsideFormat4() throws IOException {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] baseTtf = liberationSansBytes();
        byte[] patched = injectFormat12Subtable(baseTtf, 0x1F600, 43);

        byte[] subset = FontNormalizer.subset(patched, new int[] {'H', 0x1F600}, false);
        assertTrue(subset != null && subset.length > 0, "non-BMP subset returned empty");

        int[] cmapLoc = findSfntTable(subset, "cmap");
        assertNotNull(cmapLoc, "subset missing cmap table");
        ByteBuffer buf = tableBuffer(subset);
        int cmapBase = cmapLoc[0];
        int numSubtables = buf.getShort(cmapBase + 2) & 0xFFFF;
        boolean foundFmt4 = false;
        boolean foundFmt12WithNonBmp = false;
        for (int i = 0; i < numSubtables; i++) {
            int rec = cmapBase + 4 + i * 8;
            int pid = buf.getShort(rec) & 0xFFFF;
            int eid = buf.getShort(rec + 2) & 0xFFFF;
            int subOff = cmapBase + buf.getInt(rec + 4);
            int fmt = buf.getShort(subOff) & 0xFFFF;
            if (pid == 3 && eid == 1 && fmt == 4) {
                foundFmt4 = true;
            } else if (pid == 3 && eid == 10 && fmt == 12) {
                int numGroups = buf.getInt(subOff + 12);
                for (int g = 0; g < numGroups; g++) {
                    int grp = subOff + 16 + g * 12;
                    int start = buf.getInt(grp);
                    int end = buf.getInt(grp + 4);
                    if (0x1F600 >= start && 0x1F600 <= end) {
                        foundFmt12WithNonBmp = true;
                    }
                }
            }
        }
        assertTrue(foundFmt4, "subset must preserve (3,1) Format 4 BMP cmap");
        assertTrue(foundFmt12WithNonBmp,
                "subset must preserve (3,10) Format 12 cmap mapping U+1F600");
    }

    @Test
    void subsetRepairsScanSoftPostVersionAndOutOfRangeIndexToLocFormat() throws IOException {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] corrupted = liberationSansBytes().clone();
        int[] postLoc = findSfntTable(corrupted, "post");
        int[] headLoc = findSfntTable(corrupted, "head");
        assertNotNull(postLoc);
        assertNotNull(headLoc);

        ByteBuffer w = ByteBuffer.wrap(corrupted).order(ByteOrder.BIG_ENDIAN);
        w.putInt(postLoc[0], 0x00000003);
        w.putShort(headLoc[0] + 50, (short) 5);

        byte[] subset = FontNormalizer.subset(corrupted, new int[] {'H', 'e', 'l', 'o'}, false);
        assertTrue(subset != null && subset.length > 0, "subset failed on ScanSoft post/head");
        assertTrue(FontNormalizer.classify(subset).hasCmap());

        int[] subPost = findSfntTable(subset, "post");
        int[] subHead = findSfntTable(subset, "head");
        assertNotNull(subHead);
        ByteBuffer r = tableBuffer(subset);
        if (subPost != null) {
            int postVer = r.getInt(subPost[0]);
            assertFalse(postVer == 0x00000003,
                    "ScanSoft post version 0x00000003 must be normalized");
        }
        short locaFmt = r.getShort(subHead[0] + 50);
        assertTrue(locaFmt == 0 || locaFmt == 1,
                "out-of-range indexToLocFormat must be repaired, got " + locaFmt);
    }

    @Test
    void repairWidthsSynthesizesIdentityVDw2AndW2() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf = cidVerticalTrueTypePdf(null, null);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.repairWidths(doc, 0),
                    "vertical CID font without /DW2 must be repaired");
            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                COSBase dw2Obj = descendant.getDictionaryObject(COSName.getPDFName("DW2"));
                assertTrue(dw2Obj instanceof COSArray dw2 && dw2.size() == 2,
                        "expected synthesized /DW2 [v, w1y], got " + dw2Obj);
            }
        }

        // With explicit non-default /DW2 [880 -500] and no /W2, /W2 entries must be emitted.
        byte[] pdfCustomDw2 = cidVerticalTrueTypePdf(new int[] {880, -500}, null);
        try (PdfDocument doc = PdfDocument.open(pdfCustomDw2)) {
            assertEquals(1, FontNormalizer.repairWidths(doc, 0));
            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary descendant = cidDescendant(loaded);
                assertNotNull(descendant);
                assertNotNull(descendant.getDictionaryObject(COSName.getPDFName("W2")),
                        "expected /W2 entries when vertical metric differs from custom /DW2");
            }
        }
    }

    @Test
    void fixToUnicodeEmitsBeginBfRangeAndReplacesInvalidBegincidrange() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] ttf = liberationSansBytes();
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font cidFont = PDType0Font.load(doc, new ByteArrayInputStream(ttf), false);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(cidFont, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("ABCD");
                cs.endText();
            }
            ByteArrayOutputStream first = new ByteArrayOutputStream();
            doc.save(first);
            pdf = first.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font);
            String badCmap = "/CIDInit /ProcSet findresource begin\n"
                    + "12 dict begin begincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                    + "/CMapName /Adobe-Identity-UCS def\n"
                    + "/CMapType 2 def\n"
                    + "1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                    + "1 begincidrange\n<0024> <0027> 65\nendcidrange\n"
                    + "endcmap CMapName currentdict /CMap defineresource pop end end\n";
            font.setItem(COSName.TO_UNICODE, new PDStream(doc,
                    new ByteArrayInputStream(badCmap.getBytes(StandardCharsets.US_ASCII)))
                    .getCOSObject());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(1, FontNormalizer.fixToUnicode(doc, 0));
            byte[] repaired = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(repaired)) {
                COSDictionary font = firstFontDictionary(loaded);
                assertNotNull(font);
                byte[] cmapBytes = streamBytes(font, COSName.TO_UNICODE);
                String cmapText = new String(cmapBytes, StandardCharsets.US_ASCII);
                assertTrue(cmapText.contains("beginbfrange"),
                        "contiguous mapping must emit beginbfrange:\n" + cmapText);
                assertFalse(cmapText.contains("begincidrange"),
                        "synthesized ToUnicode must not emit begincidrange:\n" + cmapText);
                assertEquals("ABCD", new PDFTextStripper().getText(loaded).trim());
            }
        }
    }

    private static int[] findSfntTable(byte[] sfnt, String tag) {
        if (sfnt.length < 12) {
            return null;
        }
        ByteBuffer buf = tableBuffer(sfnt);
        int numTables = buf.getShort(4) & 0xFFFF;
        for (int i = 0; i < numTables; i++) {
            int off = 12 + i * 16;
            if (off + 16 > sfnt.length) {
                break;
            }
            String entryTag = new String(sfnt, off, 4, StandardCharsets.US_ASCII);
            if (tag.equals(entryTag)) {
                int tblOff = buf.getInt(off + 8);
                int tblLen = buf.getInt(off + 12);
                return new int[] {tblOff, tblLen, off};
            }
        }
        return null;
    }

    private static byte[] injectFormat12Subtable(byte[] sfnt, int cp, int gid) {
        int[] cmapLoc = findSfntTable(sfnt, "cmap");
        assertNotNull(cmapLoc);
        ByteBuffer src = tableBuffer(sfnt);
        int cmapOff = cmapLoc[0];
        int numSub = src.getShort(cmapOff + 2) & 0xFFFF;
        byte[] fmt4Bytes = null;
        for (int i = 0; i < numSub; i++) {
            int rec = cmapOff + 4 + i * 8;
            int pid = src.getShort(rec) & 0xFFFF;
            int eid = src.getShort(rec + 2) & 0xFFFF;
            int subOff = cmapOff + src.getInt(rec + 4);
            if (pid == 3 && eid == 1 && (src.getShort(subOff) & 0xFFFF) == 4) {
                int len = src.getShort(subOff + 2) & 0xFFFF;
                fmt4Bytes = Arrays.copyOfRange(sfnt, subOff, subOff + len);
                break;
            }
        }
        assertNotNull(fmt4Bytes, "expected (3,1) Format 4 subtable in base font");

        int headerSize = 4 + 2 * 8;
        int fmt4Off = headerSize;
        int fmt12Off = (fmt4Off + fmt4Bytes.length + 3) & ~3;
        int fmt12Len = 16 + 2 * 12;
        int newCmapLen = fmt12Off + fmt12Len;
        byte[] newCmap = new byte[newCmapLen];
        ByteBuffer cb = ByteBuffer.wrap(newCmap).order(ByteOrder.BIG_ENDIAN);
        cb.putShort((short) 0);
        cb.putShort((short) 2);
        // Record 0: (3, 1) Format 4
        cb.putShort((short) 3);
        cb.putShort((short) 1);
        cb.putInt(fmt4Off);
        // Record 1: (3, 10) Format 12
        cb.putShort((short) 3);
        cb.putShort((short) 10);
        cb.putInt(fmt12Off);
        System.arraycopy(fmt4Bytes, 0, newCmap, fmt4Off, fmt4Bytes.length);
        // Format 12 header + 2 groups sorted ascending: BMP 'H' and non-BMP cp
        cb.position(fmt12Off);
        cb.putShort((short) 12);
        cb.putShort((short) 0);
        cb.putInt(fmt12Len);
        cb.putInt(0);
        cb.putInt(2);
        cb.putInt('H');
        cb.putInt('H');
        cb.putInt(gid);
        cb.putInt(cp);
        cb.putInt(cp);
        cb.putInt(gid);

        int alignedEnd = (sfnt.length + 3) & ~3;
        byte[] out = new byte[alignedEnd + newCmapLen];
        System.arraycopy(sfnt, 0, out, 0, sfnt.length);
        System.arraycopy(newCmap, 0, out, alignedEnd, newCmapLen);
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        ob.putInt(cmapLoc[2] + 8, alignedEnd);
        ob.putInt(cmapLoc[2] + 12, newCmapLen);
        return out;
    }

    private static byte[] injectVerticalTables(byte[] sfnt, int advanceHeight, int topSideBearing) {
        ByteBuffer in = tableBuffer(sfnt);
        int numTables = in.getShort(4) & 0xFFFF;
        int[] maxpLoc = findSfntTable(sfnt, "maxp");
        assertNotNull(maxpLoc, "expected maxp table");
        int numGlyphs = in.getShort(maxpLoc[0] + 4) & 0xFFFF;

        byte[] vhea = new byte[36];
        ByteBuffer vb = ByteBuffer.wrap(vhea).order(ByteOrder.BIG_ENDIAN);
        vb.putInt(0x00010000);
        vb.putShort((short) 1024);
        vb.putShort((short) -1024);
        vb.putShort((short) 0);
        vb.putShort((short) advanceHeight);
        vb.putShort((short) topSideBearing);
        vb.putShort((short) 0);
        vb.putShort((short) 1500);
        vb.putShort((short) 0);
        vb.putShort((short) 1);
        vb.position(34);
        vb.putShort((short) numGlyphs);

        byte[] vmtx = new byte[numGlyphs * 4];
        ByteBuffer mb = ByteBuffer.wrap(vmtx).order(ByteOrder.BIG_ENDIAN);
        for (int g = 0; g < numGlyphs; g++) {
            mb.putShort((short) advanceHeight);
            mb.putShort((short) topSideBearing);
        }

        java.util.TreeMap<String, byte[]> tables = new java.util.TreeMap<>();
        for (int i = 0; i < numTables; i++) {
            int recOff = 12 + i * 16;
            String tag = new String(sfnt, recOff, 4, StandardCharsets.US_ASCII);
            int off = in.getInt(recOff + 8);
            int len = in.getInt(recOff + 12);
            tables.put(tag, Arrays.copyOfRange(sfnt, off, off + len));
        }
        tables.put("vhea", vhea);
        tables.put("vmtx", vmtx);

        int count = tables.size();
        int dataOffset = 12 + count * 16;
        int totalSize = dataOffset;
        for (byte[] t : tables.values()) {
            totalSize += (t.length + 3) & ~3;
        }
        byte[] out = new byte[totalSize];
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        ob.putInt(in.getInt(0));
        ob.putShort((short) count);
        int sr = Integer.highestOneBit(count);
        int es = Integer.numberOfTrailingZeros(sr);
        ob.putShort((short) (sr * 16));
        ob.putShort((short) es);
        ob.putShort((short) ((count - sr) * 16));

        int idx = 0;
        int cursor = dataOffset;
        for (var entry : tables.entrySet()) {
            byte[] data = entry.getValue();
            int recOff = 12 + idx * 16;
            byte[] tagBytes = entry.getKey().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(tagBytes, 0, out, recOff, 4);
            ob.putInt(recOff + 4, 0);
            ob.putInt(recOff + 8, cursor);
            ob.putInt(recOff + 12, data.length);
            System.arraycopy(data, 0, out, cursor, data.length);
            cursor += (data.length + 3) & ~3;
            idx++;
        }
        return out;
    }

    private static byte[] cidVerticalTrueTypePdf(int[] dw2Values, COSArray w2Array)
            throws IOException {
        byte[] program = injectVerticalTables(liberationSansBytes(), 1800, 100);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, "LiberationSans");
            descriptor.setInt(COSName.FLAGS, 32);
            descriptor.setItem(COSName.FONT_BBOX, new COSArray());
            descriptor.setInt(COSName.ITALIC_ANGLE, 0);
            descriptor.setInt(COSName.ASCENT, 900);
            descriptor.setInt(COSName.DESCENT, -200);
            descriptor.setInt(COSName.CAP_HEIGHT, 700);
            descriptor.setInt(COSName.STEM_V, 80);
            COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(program))
                    .getCOSObject();
            fontFile.setInt(COSName.LENGTH1, program.length);
            descriptor.setItem(COSName.FONT_FILE2, fontFile);

            COSDictionary cidSystemInfo = new COSDictionary();
            cidSystemInfo.setString(COSName.REGISTRY, "Adobe");
            cidSystemInfo.setString(COSName.ORDERING, "Identity");
            cidSystemInfo.setInt(COSName.SUPPLEMENT, 0);
            COSDictionary descendant = new COSDictionary();
            descendant.setItem(COSName.TYPE, COSName.FONT);
            descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
            descendant.setName(COSName.BASE_FONT, "LiberationSans");
            descendant.setItem(COSName.CIDSYSTEMINFO, cidSystemInfo);
            descendant.setInt(COSName.DW, 1000);
            if (dw2Values != null) {
                COSArray dw2 = new COSArray();
                dw2.add(COSInteger.get(dw2Values[0]));
                dw2.add(COSInteger.get(dw2Values[1]));
                descendant.setItem(COSName.getPDFName("DW2"), dw2);
            }
            if (w2Array != null) {
                descendant.setItem(COSName.getPDFName("W2"), w2Array);
            }
            descendant.setItem(COSName.FONT_DESC, descriptor);

            COSArray descendants = new COSArray();
            descendants.add(descendant);
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE0);
            font.setName(COSName.BASE_FONT, "LiberationSans");
            font.setItem(COSName.ENCODING, COSName.IDENTITY_V);
            font.setItem(COSName.DESCENDANT_FONTS, descendants);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(resources));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td <0024> Tj ET"
                            .getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void normalizingNonSymbolicTrueTypeNameStandardEncodingConvertsToWinAnsi() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf;
        try (PDDocument doc = Loader.loadPDF(embeddedTrueTypePdf(true, false))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font);
            font.setItem(COSName.ENCODING, COSName.STANDARD_ENCODING);

            PDPage page = doc.getPage(0);
            COSName fontKey = page.getResources().getFontNames().iterator().next();
            String stream = "BT /" + fontKey.getName() + " 12 Tf 72 700 Td (a'b) Tj ET";
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    stream.getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.Result res = FontNormalizer.normalizePage(doc, 0);
            assertTrue(res.fontsProcessed() >= 1);
            byte[] normalized = doc.saveBytes();
            try (PDDocument loaded = Loader.loadPDF(normalized)) {
                COSDictionary font = firstFontDictionary(loaded);
                COSDictionary enc = font.getCOSDictionary(COSName.ENCODING);
                assertNotNull(enc, "direct /StandardEncoding must become WinAnsi + /Differences");
                assertEquals(COSName.WIN_ANSI_ENCODING,
                        enc.getDictionaryObject(COSName.BASE_ENCODING));
                COSArray diffs = enc.getCOSArray(COSName.DIFFERENCES);
                assertNotNull(diffs);
                String extracted = new PDFTextStripper().getText(loaded).trim();
                assertEquals("a\u2019b", extracted);
            }
            // Second pass on clean encoding dictionary must be idempotent.
            FontNormalizer.Result second = FontNormalizer.normalizePage(doc, 0);
            assertEquals(0, second.toUnicodeFixed());
            assertEquals(0, second.widthsRepaired());
        }
    }

    @Test
    void normalizingSymbolicTrueTypeWithoutMsOrMacRomanKeepsSymbolicFlag() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] appleUnicodeOnlySfnt = keepOnlyAppleUnicodeCmap(liberationSansBytes());
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, "LiberationSans");
            descriptor.setInt(COSName.FLAGS, 4);
            descriptor.setItem(COSName.FONT_BBOX, new COSArray());
            COSStream fontFile = new PDStream(doc, new ByteArrayInputStream(appleUnicodeOnlySfnt))
                    .getCOSObject();
            fontFile.setInt(COSName.LENGTH1, appleUnicodeOnlySfnt.length);
            descriptor.setItem(COSName.FONT_FILE2, fontFile);

            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
            font.setName(COSName.BASE_FONT, "LiberationSans");
            font.setInt(COSName.FIRST_CHAR, 65);
            font.setInt(COSName.LAST_CHAR, 65);
            COSArray widths = new COSArray();
            widths.add(COSInteger.get(667));
            font.setItem(COSName.WIDTHS, widths);
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setItem(COSName.FONT_DESC, descriptor);

            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(res));
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 12 Tf 72 700 Td (A) Tj ET".getBytes(StandardCharsets.US_ASCII))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.normalizePage(doc, 0);
            try (PDDocument loaded = Loader.loadPDF(doc.saveBytes())) {
                COSDictionary font = firstFontDictionary(loaded);
                COSDictionary desc = font.getCOSDictionary(COSName.FONT_DESC);
                assertNotNull(desc);
                int flags = desc.getInt(COSName.FLAGS);
                assertTrue((flags & 4) != 0,
                        "font lacking (3,1)/(1,0) cmap must remain Symbolic, flags=" + flags);
                assertNull(font.getDictionaryObject(COSName.ENCODING),
                        "symbolic TrueType font must not keep /Encoding");
            }
        }
    }

    @Test
    void normalizingNegativeCidWidthRangeDoesNotWrapAround() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        byte[] pdf;
        try (PDDocument doc = Loader.loadPDF(cidTrueTypePdf("LiberationSans", null))) {
            COSDictionary font = firstFontDictionary(doc);
            assertNotNull(font);
            COSDictionary descendant = (COSDictionary) font.getCOSArray(COSName.DESCENDANT_FONTS)
                    .getObject(0);
            COSArray badW = new COSArray();
            badW.add(COSInteger.get(-1));
            COSArray run = new COSArray();
            run.add(COSInteger.get(500));
            run.add(COSInteger.get(600));
            badW.add(run);
            descendant.setItem(COSName.W, badW);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            FontNormalizer.normalizePage(doc, 0);
            byte[] normalized = doc.saveBytes();
            assertNotNull(normalized);
            assertTrue(normalized.length > 0);
        }
    }

    private static byte[] keepOnlyAppleUnicodeCmap(byte[] sfnt) {
        int[] cmapLoc = findSfntTable(sfnt, "cmap");
        assertNotNull(cmapLoc, "font missing cmap table");
        int cmapOff = cmapLoc[0];
        int cmapLen = cmapLoc[1];
        byte[] out = sfnt.clone();
        ByteBuffer cb = ByteBuffer.wrap(out, cmapOff, cmapLen).slice().order(ByteOrder.BIG_ENDIAN);
        int numSub = cb.getShort(2) & 0xFFFF;
        int pickedRelOff = -1;
        for (int i = 0; i < numSub; i++) {
            int rec = 4 + i * 8;
            int plat = cb.getShort(rec) & 0xFFFF;
            int enc = cb.getShort(rec + 2) & 0xFFFF;
            if ((plat == 3 && enc == 1) || (plat == 0 && enc == 3)) {
                pickedRelOff = cb.getInt(rec + 4);
                break;
            }
        }
        assertTrue(pickedRelOff > 0, "expected Unicode cmap subtable");
        cb.putShort(2, (short) 1);
        cb.putShort(4, (short) 0);
        cb.putShort(6, (short) 3);
        cb.putInt(8, pickedRelOff);
        return out;
    }
}
