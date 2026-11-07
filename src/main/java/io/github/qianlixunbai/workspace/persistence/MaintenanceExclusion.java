package io.github.qianlixunbai.workspace.persistence;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.HexFormat;

/** An already-held OS exclusion, not proof of stopped external SQL/old clients/model execution.
 * NativeGate must admit the real authority before this type initializes JNA. No credential,
 * backup, receipt publication or ordinary Runtime permission is granted by this object. */
public final class MaintenanceExclusion implements AutoCloseable {
    private final Path root,runtimeFile,modelDirectory,modelFile;
    private final HANDLE transferred;
    private HANDLE runtimePin,modelPin;
    private FileChannel model;
    private String runtimeIdentity,modelIdentity,modelDirectoryIdentity;
    private boolean closed;

    private MaintenanceExclusion(Path root,long handle){
        this.root=root;runtimeFile=root.resolve("evidence/runtime-admission.lock");
        modelDirectory=root.resolve("model-state");modelFile=modelDirectory.resolve(".lock");
        transferred=new HANDLE(Pointer.createConstant(handle));
    }

    /** Called only by NativeGate after original admission; consumes the duplicate owned by this
     * child. The numeric handle is received on the native owner's private stdin, never reopened
     * from a PID, a file path supplied by a request, or a saved receipt. */
    public static MaintenanceExclusion acceptTransferred(Path verifiedRoot,long handle) throws IOException {
        WindowsPrivateAcl.verify(verifiedRoot,true); // Also enforces original NativeGate first.
        WindowsPrivateAcl.verify(verifiedRoot.resolve("evidence"),true);
        WindowsPrivateAcl.verify(verifiedRoot.resolve("model-state"),true);
        var result=receiveHandles(verifiedRoot,handle);
        try {result.requireHeld(verifiedRoot);return result;}
        catch(IOException|RuntimeException|Error failure){result.close();throw failure;}
    }

    // Private OS-level seam for a fresh synthetic fixture; never a replacement Native Gate.
    private static MaintenanceExclusion receiveHandles(Path root,long handle) throws IOException {
        if(handle==0||handle==-1||!root.equals(root.toAbsolutePath().normalize()))throw stopped();
        NativeProfileRecovery.requireOrdinaryProfile(root);
        var result=new MaintenanceExclusion(root,handle);
        try {
            result.runtimeIdentity=identity(result.transferred,result.runtimeFile,false,true);
            // FlushFileBuffers requires write access. A read-only handle/ordinary Runtime lease
            // cannot pass. Any retained writer excludes Runtime's READ + NOSHARE_WRITE open.
            if(!Kernel32.INSTANCE.FlushFileBuffers(result.transferred))throw stopped();
            // Retain an independent no-delete metadata pin, even if a hostile transferred
            // handle had allowed deletion. Neither name nor parent may be replaced underneath us.
            result.runtimePin=openMetadata(result.runtimeFile,false);
            result.modelPin=openMetadata(result.modelDirectory,true);
            result.modelDirectoryIdentity=identity(result.modelPin,result.modelDirectory,true,false);
            result.modelIdentity=TrustedDatabaseConfiguration.Identity.read(result.modelFile,false);
            result.model=FileChannel.open(result.modelFile,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE);
            result.requireHandles(root);
            return result;
        }catch(IOException|RuntimeException|Error failure){result.close();throw failure;}
    }

    public void requireHeld(Path expectedRoot) throws IOException {
        requireHandles(expectedRoot);
        NativeProfileRecovery.requireOrdinaryProfile(root);
        WindowsPrivateAcl.verify(root,true);WindowsPrivateAcl.verify(runtimeFile.getParent(),true);
        WindowsPrivateAcl.verify(runtimeFile,false);WindowsPrivateAcl.verify(modelDirectory,true);WindowsPrivateAcl.verify(modelFile,false);
    }

    void requireHandles(Path expectedRoot) throws IOException {
        if(closed||!root.equals(expectedRoot)||model==null||!model.isOpen()||model.size()!=0
            ||!runtimeIdentity.equals(identity(transferred,runtimeFile,false,true))
            ||!runtimeIdentity.equals(identity(runtimePin,runtimeFile,false,true))
            ||!modelDirectoryIdentity.equals(identity(modelPin,modelDirectory,true,false))
            ||!modelIdentity.equals(TrustedDatabaseConfiguration.Identity.read(modelFile,false))
            ||!Kernel32.INSTANCE.FlushFileBuffers(transferred))throw stopped();
    }

    private static HANDLE openMetadata(Path path,boolean directory) throws IOException {
        HANDLE result=Kernel32.INSTANCE.CreateFile("\\\\?\\"+path,directory?0x20081:0x20080,3,null,3,0x02200000,null);
        if(WinBase.INVALID_HANDLE_VALUE.equals(result))throw stopped();
        try{identity(result,path,directory,!directory);return result;}
        catch(IOException|RuntimeException|Error failure){Kernel32.INSTANCE.CloseHandle(result);throw failure;}
    }

    private static String identity(HANDLE handle,Path path,boolean directory,boolean empty) throws IOException {
        if(handle==null||Kernel32.INSTANCE.GetFileType(handle)!=1)throw stopped();
        var tag=new WinBase.FILE_ATTRIBUTE_TAG_INFO();
        if(!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,9,tag.getPointer(),new WinDef.DWORD(tag.size())))throw stopped();
        tag.read();if((tag.FileAttributes&0x400)!=0||((tag.FileAttributes&0x10)!=0)!=directory)throw stopped();
        // WinBase.h uses one-byte BOOLEANs at offsets 20/21, not JNA 5.17's four-byte
        // boolean fields. Read the exact 24-byte Windows layout; padding is not state.
        try(var info=new com.sun.jna.Memory(24)) {
            info.clear();
            if(!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,1,info,new WinDef.DWORD(24)))throw stopped();
            if(info.getByte(20)!=0||(info.getByte(21)!=0)!=directory
                ||(!directory&&info.getInt(16)!=1)||(empty&&info.getLong(8)!=0))throw stopped();
        }
        char[] finalPath=new char[32768];
        var paths=com.sun.jna.Native.load("kernel32",TrustedDatabaseConfiguration.Identity.Paths.class);
        int count=paths.GetFinalPathNameByHandleW(handle,finalPath,finalPath.length,0);
        if(count<1||count>=finalPath.length||!new String(finalPath,0,count).equalsIgnoreCase("\\\\?\\"+path))throw stopped();
        var id=new WinBase.FILE_ID_INFO();
        if(!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,18,id.getPointer(),new WinDef.DWORD(id.size())))throw stopped();
        return HexFormat.of().formatHex(id.getPointer().getByteArray(0,id.size()));
    }

    // Gate retains the object for process lifetime. A caller close is permanent STOP, never
    // permission to switch to ordinary Runtime or silently reacquire a less exclusive lease.
    @Override public void close() throws IOException {
        if(closed)return;closed=true;
        try{if(model!=null)model.close();}
        finally{
            if(modelPin!=null)Kernel32.INSTANCE.CloseHandle(modelPin);
            if(runtimePin!=null)Kernel32.INSTANCE.CloseHandle(runtimePin);
            Kernel32.INSTANCE.CloseHandle(transferred);
        }
    }
    private static IOException stopped(){return new IOException("Maintenance exclusion unavailable; no operation admitted");}
    @Override public String toString(){return "MaintenanceExclusion[non-authorizing]";}
}
