package io.github.qianlixunbai.workspace.backup;

import org.bouncycastle.bcpg.*;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** No PG connections: exercise the existing PortableEncryptedBackup wrappers and independently
 * decode their standard packets after extracting the shared stream codec. */
class OpenPgpStreamsTest {
    @Test void existingPortableVersionsKeepTheirLiteralAndCryptoContract()throws Exception{
        var encrypt=PortableEncryptedBackup.class.getDeclaredMethod("encryptZip",OutputStream.class,char[].class,String.class,OpenPgpStreams.ZipContents.class);
        var decrypt=PortableEncryptedBackup.class.getDeclaredMethod("decryptBundle",InputStream.class,char[].class,String.class,OpenPgpStreams.ClearContents.class);
        encrypt.setAccessible(true);decrypt.setAccessible(true);char[] secret=UUID.randomUUID().toString().toCharArray();
        try{
            for(String name:List.of("workspace-b3-v1.zip","workspace-b3-v2.zip","workspace-v3.zip")){
                var output=new ByteArrayOutputStream();
                OpenPgpStreams.ZipContents writer=zip->{zip.putNextEntry(new ZipEntry("manifest.json"));zip.write("synthetic legacy codec".getBytes(StandardCharsets.UTF_8));zip.closeEntry();};
                encrypt.invoke(null,output,secret,name,writer);byte[] encoded=output.toByteArray();
                var packets=new BCPGInputStream(new ByteArrayInputStream(encoded));var key=(SymmetricKeyEncSessionPacket)packets.readPacket();
                assertEquals(6,key.getVersion());assertEquals(SymmetricKeyAlgorithmTags.AES_256,key.getEncAlgorithm());assertEquals(AEADAlgorithmTags.GCM,key.getAeadAlgorithm());
                assertEquals(S2K.ARGON_2,key.getS2K().getType());assertEquals(16,key.getS2K().getMemorySizeExponent());assertEquals(3,key.getS2K().getPasses());assertEquals(4,key.getS2K().getParallelism());
                var objects=new BcPGPObjectFactory(encoded);var encrypted=(PGPEncryptedDataList)objects.nextObject();var data=(PGPPBEEncryptedData)encrypted.get(0);
                assertTrue(data.isAEAD());var factory=new BcPBEDataDecryptorFactory(secret,new BcPGPDigestCalculatorProvider());
                try(var clear=data.getDataStream(factory)){
                    var inner=new BcPGPObjectFactory(clear);var literal=(PGPLiteralData)inner.nextObject();assertEquals(name,literal.getFileName());
                    var bytes=literal.getDataStream().readAllBytes();assertNull(inner.nextObject());assertEquals(-1,clear.read());assertTrue(data.verify());
                    try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){assertEquals("manifest.json",zip.getNextEntry().getName());assertEquals("synthetic legacy codec",new String(zip.readAllBytes(),StandardCharsets.UTF_8));assertNull(zip.getNextEntry());}
                }
                assertNull(objects.nextObject());
                OpenPgpStreams.ClearContents sink=input->input.transferTo(OutputStream.nullOutputStream());
                decrypt.invoke(null,new ByteArrayInputStream(encoded),secret,name,sink);
                assertThrows(InvocationTargetException.class,()->decrypt.invoke(null,new ByteArrayInputStream(encoded),secret,"foreign-literal.zip",sink));
            }
        }finally{Arrays.fill(secret,'\0');}
    }
}
