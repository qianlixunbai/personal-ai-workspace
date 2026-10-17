package io.github.qianlixunbai.workspace.capability.vision;

import io.github.qianlixunbai.workspace.common.WorkspaceException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisionInputTest {
    @Test void boundedImageAndTokenPolicyRejectUntrustedInput() throws Exception {
        BufferedImage source = new BufferedImage(16,8,BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0,0,0x80000000);
        byte[] png = encode(source,"png");
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        byte[] withMetadata=metadata(png,"iCCP",new byte[8192]);
        String body=json.writeValueAsString(java.util.Map.of("taskId",java.util.UUID.randomUUID().toString(),"question","synthetic","expectedSelectionRevision",1,
            "image",java.util.Map.of("mediaType","image/png","dataBase64",java.util.Base64.getEncoder().encodeToString(withMetadata))));
        assertArrayEquals(withMetadata,VisionRequest.read(new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))).image());
        for(String invalid:java.util.List.of(body+" {}",body.replace("\"question\":\"synthetic\"","\"question\":\"synthetic\",\"question\":\"again\""),
                body.replaceFirst("\\{","{\"unknown\":1,"),body.replace("iVBOR","iV BOR")))
            assertThrows(WorkspaceException.class,()->VisionRequest.read(new ByteArrayInputStream(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThrows(WorkspaceException.class,()->VisionRequest.read(new ByteArrayInputStream(new byte[VisionRequest.MAX_BODY+1])));
        assertEquals(16,VisionImage.normalize(withMetadata,"image/png").width());
        assertThrows(WorkspaceException.class,()->VisionImage.normalize(metadata(png,"acTL",new byte[8]),"image/png"));
        var normalized = VisionImage.normalize(png,"image/png");
        assertEquals(16,normalized.width()); assertEquals(8,normalized.height());
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(normalized.png()))) {
            reader.setInput(stream,false,true); var image = reader.read(0);
            assertEquals(1024,image.getWidth()); assertEquals(1024,image.getHeight());
            assertFalse(image.getColorModel().hasAlpha());
            assertEquals(0xffffff,image.getRGB(0,0)&0xffffff);
            assertEquals(0x7f7f7f,image.getRGB(0,256)&0xffffff); image.flush();
        } finally { reader.dispose(); }
        byte[] damaged = png.clone(); damaged[damaged.length-1]^=1;
        assertThrows(WorkspaceException.class,()->VisionImage.normalize(damaged,"image/png"));
        assertThrows(WorkspaceException.class,()->VisionImage.normalize(Arrays.copyOf(png,png.length+1),"image/png"));
        assertThrows(WorkspaceException.class,()->VisionImage.normalize(png,"image/jpeg"));
        assertThrows(WorkspaceException.class,()->VisionImage.normalize(new byte[VisionImage.MAX_BYTES+1],"image/png"));
        BufferedImage rgb = new BufferedImage(16,8,BufferedImage.TYPE_INT_RGB);
        assertEquals(16,VisionImage.normalize(encode(rgb,"jpeg"),"image/jpeg").width()); rgb.flush();
        BufferedImage deep=new BufferedImage(16,8,BufferedImage.TYPE_USHORT_GRAY);
        assertEquals(16,VisionImage.normalize(encode(deep,"png"),"image/png").width());deep.flush();
        BufferedImage tooWide = new BufferedImage(4097,1,BufferedImage.TYPE_INT_RGB);
        byte[] oversized = encode(tooWide,"png"); tooWide.flush();
        assertThrows(WorkspaceException.class,()->VisionImage.normalize(oversized,"image/png"));
        String a="a".repeat(64), b="b".repeat(64), c="c".repeat(64);
        var proof = new VisionTokenBudget.Evidence("fixture",a,b,c,VisionTokenBudget.POLICY,4096,256,1024,512);
        VisionTokenBudget.verify(proof,"fixture",a,b,c,"Describe the image.");
        assertThrows(WorkspaceException.class,()->VisionTokenBudget.verify(null,"fixture",a,b,c,"Describe the image."));
        assertThrows(WorkspaceException.class,()->VisionTokenBudget.verify(proof,"fixture",b,b,c,"Describe the image."));
        var over = new VisionTokenBudget.Evidence("fixture",a,b,c,VisionTokenBudget.POLICY,4096,2048,1024,512);
        assertThrows(WorkspaceException.class,()->VisionTokenBudget.verify(over,"fixture",a,b,c,"Describe the image."));
        source.flush();
    }
    private static byte[] encode(BufferedImage image,String format) throws Exception {
        var bytes = new ByteArrayOutputStream(); var writer = ImageIO.getImageWritersByFormatName(format).next();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);var parameters=writer.getDefaultWriteParam();if(format.equals("jpeg"))parameters.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null,new IIOImage(image,null,null),parameters); output.flush();
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
    private static byte[] metadata(byte[] png,String type,byte[] payload) throws Exception {
        var out=new ByteArrayOutputStream();out.write(png,0,33);var bytes=new java.io.DataOutputStream(out);bytes.writeInt(payload.length);
        byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.write(name);out.write(payload);var crc=new java.util.zip.CRC32();crc.update(name);crc.update(payload);bytes.writeInt((int)crc.getValue());out.write(png,33,png.length-33);return out.toByteArray();
    }
}
