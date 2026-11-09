package io.github.qianlixunbai.workspace.backup;

import org.bouncycastle.bcpg.*;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.require;

/** The existing PortableEncryptedBackup RFC 9580 stream implementation, shared without
 * sharing its SQL/archive admission. Callers own file pins, limits, schemas and publication.
 * This is a stream codec, not a backup entry point or a new cryptographic protocol. */
public final class OpenPgpStreams {
    private OpenPgpStreams() {}
    private static final int BUFFER=65536;
    @FunctionalInterface public interface ZipContents {void write(ZipOutputStream zip) throws Exception;}
    @FunctionalInterface public interface ClearContents {void read(InputStream input) throws Exception;}

    public static void encryptZip(OutputStream output,char[] password,String literalName,long maximum,ZipContents contents) throws Exception {
        var random=new SecureRandom();var builder=new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
            .setUseV6AEAD().setWithAEAD(AEADAlgorithmTags.GCM,16).setSecureRandom(random);
        var generator=new PGPEncryptedDataGenerator(builder);generator.setForceSessionKey(true);
        generator.addMethod(new BcPBEKeyEncryptionMethodGenerator(password,S2K.Argon2Params.memoryConstrainedParameters()).setSecureRandom(random));
        var shield=new FilterOutputStream(output){
            @Override public void write(byte[] bytes,int offset,int length)throws IOException{out.write(bytes,offset,length);}
            @Override public void close()throws IOException{flush();}
        };
        try(var crypt=generator.open(shield,new byte[BUFFER]);
            var literal=new PGPLiteralDataGenerator().open(crypt,PGPLiteralData.BINARY,literalName,new Date(0),new byte[BUFFER]);
            var bounded=new FilterOutputStream(literal){
                long bytes;
                @Override public void write(int value)throws IOException{bytes++;require(bytes<=maximum);out.write(value);}
                @Override public void write(byte[] b,int off,int len)throws IOException{bytes=Math.addExact(bytes,len);require(bytes<=maximum);out.write(b,off,len);}
            };
            var zip=new ZipOutputStream(bounded,StandardCharsets.UTF_8)){
            zip.setLevel(Deflater.NO_COMPRESSION);contents.write(zip);
        }
    }

    /** The consumer must drain the literal stream. Do not publish its output until this
     * method returns: the final authentication tag is checked after the consumer. */
    public static void decryptBundle(InputStream input,char[] password,String literalName,ClearContents contents) throws Exception {
        var objects=new BcPGPObjectFactory(input);Object first=objects.nextObject();
        require(first instanceof PGPEncryptedDataList);
        var list=(PGPEncryptedDataList)first;require(list.size()==1&&list.get(0) instanceof PGPPBEEncryptedData);
        var data=(PGPPBEEncryptedData)list.get(0);require(data.getVersion()==6&&data.isAEAD());
        var decryptor=new BcPBEDataDecryptorFactory(password,new BcPGPDigestCalculatorProvider());
        try(var clear=data.getDataStream(decryptor)){
            var inner=new BcPGPObjectFactory(clear);Object literal=inner.nextObject();require(literal instanceof PGPLiteralData);
            var file=(PGPLiteralData)literal;require(file.getFormat()==PGPLiteralData.BINARY&&file.getFileName().equals(literalName));
            contents.read(file.getDataStream());
            require(inner.nextObject()==null);require(clear.read()==-1);require(data.verify());
            require(objects.nextObject()==null);
        }
    }

    /** Reject hostile KDF/chunk parameters before allocating Argon2 or AEAD work memory. */
    public static void profile(FileChannel payload)throws Exception{
        var header=new BCPGInputStream(new LimitedInputStream(Channels.newInputStream(payload),65536));
        var first=header.readPacket();require(first instanceof SymmetricKeyEncSessionPacket);
        var key=(SymmetricKeyEncSessionPacket)first;
        require(key.getVersion()==6&&key.getEncAlgorithm()==SymmetricKeyAlgorithmTags.AES_256&&key.getAeadAlgorithm()==AEADAlgorithmTags.GCM);
        var kdf=key.getS2K();require(kdf.getType()==S2K.ARGON_2&&kdf.getMemorySizeExponent()==16&&kdf.getPasses()==3&&kdf.getParallelism()==4);
        var second=header.readPacket();require(second instanceof SymmetricEncIntegrityPacket);
        var data=(SymmetricEncIntegrityPacket)second;
        require(data.getVersion()==2&&data.getCipherAlgorithm()==SymmetricKeyAlgorithmTags.AES_256&&data.getAeadAlgorithm()==AEADAlgorithmTags.GCM&&data.getChunkSize()==10);
    }

    public static long transfer(InputStream input,OutputStream output,long limit)throws IOException{
        byte[] buffer=new byte[BUFFER];long count=0;int n;
        try{while((n=input.read(buffer))!=-1){require(!Thread.currentThread().isInterrupted());count=Math.addExact(count,n);require(count<=limit);output.write(buffer,0,n);}return count;}
        finally{Arrays.fill(buffer,(byte)0);}
    }
    private static final class LimitedInputStream extends FilterInputStream{
        private long left;
        LimitedInputStream(InputStream input,long limit){super(input);left=limit;}
        @Override public int read()throws IOException{require(left>0);int value=super.read();if(value>=0)left--;return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{
            if(length==0)return 0;require(left>0);int n=in.read(bytes,offset,(int)Math.min(length,left));if(n>0)left-=n;return n;
        }
    }
}
