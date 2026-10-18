package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.model.ImageFormat;

class PdfImageConverterTest {

    @Test
    void imageToBytesEncodesStandardPng() throws Exception {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        byte[] bytes = PdfImageConverter.imageToBytes(img, ImageFormat.PNG, 85);
        assertNotNull(bytes);
        assertTrue(bytes.length > 8);
        assertEquals((byte) 0x89, bytes[0]);
    }

    @Test
    void imageToBytesThrowsWhenNoWriterExists() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        assertThrows(IOException.class, () ->
                PdfImageConverter.imageToBytes(img, ImageFormat.WEBP, 85));
    }
}
