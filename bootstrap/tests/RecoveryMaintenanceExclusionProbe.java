package io.github.qianlixunbai.workspace.persistence;

import com.sun.jna.platform.win32.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;

/** Explicit OS primitive probe only. The native parent creates every file in a new temporary
 * tree. This never calls NativeGate, opens credentials/SQL, or reports maintenance admission. */
public final class RecoveryMaintenanceExclusionProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IOException();Path root=Path.of(args[0]);
        if(!root.getParent().getFileName().toString().equals("Profiles")
            ||!root.getParent().getParent().getFileName().toString().startsWith("paw-maintenance-transfer-"))throw new IOException();
        Method receive=MaintenanceExclusion.class.getDeclaredMethod("receiveHandles",Path.class,long.class);
        receive.setAccessible(true);
        Method held=MaintenanceExclusion.class.getDeclaredMethod("requireHandles",Path.class);held.setAccessible(true);
        var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.US_ASCII));
        System.out.println("CHILD_PID="+ProcessHandle.current().pid());System.out.flush();
        String text=input.readLine();if(text==null||!text.matches("[0-9]{1,20}"))throw new IOException();
        try(var exclusion=(MaintenanceExclusion)receive.invoke(null,root,Long.parseLong(text))){
            System.out.println("EXCLUSION_HELD");System.out.flush();
            if(!"CHECK".equals(input.readLine()))throw new IOException();
            held.invoke(exclusion,root);
            System.out.println("EXCLUSION_STILL_HELD");System.out.flush();
            if(!"RELEASE".equals(input.readLine()))throw new IOException();
        }
        // After release, an ordinary read-only handle must not impersonate the transferred
        // writer. This negative case uses the same empty synthetic file, with no native Gate.
        var reader=Kernel32.INSTANCE.CreateFile("\\\\?\\"+root.resolve("evidence/runtime-admission.lock"),
            0x80020080,3,null,3,0x00200000,null);
        if(WinBase.INVALID_HANDLE_VALUE.equals(reader))throw new IOException();
        try {
            receive.invoke(null,root,com.sun.jna.Pointer.nativeValue(reader.getPointer()));
            throw new AssertionError("Read-only handle accepted");
        } catch(InvocationTargetException rejected){
            if(!(rejected.getCause() instanceof IOException))throw rejected;
            // receiveHandles consumed/closed this handle on rejection.
        }
        System.out.println("RELEASED_AND_READ_ONLY_REJECTED");System.out.flush();
    }
}
