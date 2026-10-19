package io.github.qianlixunbai.workspace.security;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Runtime-only Windows boundary. Existing unsafe objects are rejected, never migrated.
 * Native classes are initialized only after the Windows platform gate. */
public final class WindowsPrivateAcl {
    private WindowsPrivateAcl() {}

    public static boolean isWindows() { return System.getProperty("os.name", "").startsWith("Windows"); }

    public static void directory(Path path) throws IOException { operate(path, true, true); }
    public static void createFile(Path path) throws IOException { operate(path, false, true); }
    public static void verify(Path path, boolean directory) throws IOException { operate(path, directory, false); }

    /** SQLite creates these files by inheritance. Only the named sidecars beneath a verified
     * private parent may inherit the current-user/system/admin policy; never repaired. */
    public static void verifySqliteSidecar(Path path) throws IOException {
        if (path.getFileName() == null || !Set.of("memory.db-journal", "memory.db-wal", "memory.db-shm",
                "knowledge.db-journal", "knowledge.db-wal", "knowledge.db-shm").contains(path.getFileName().toString()))
            throw new IOException("Unknown SQLite sidecar");
        operate(path, false, false, true);
    }

    private static void operate(Path path, boolean directory, boolean create) throws IOException {
        operate(path, directory, create, false);
    }

    private static void operate(Path path, boolean directory, boolean create, boolean sidecar) throws IOException {
        if (!isWindows()) throw new IOException("Windows private ACL unavailable");
        WindowsNativeGate.admit(); // Must complete before any JNA/NativeAcl static initialization.
        try { NativeAcl.operate(path, directory, create, sidecar); }
        catch (LinkageError | RuntimeException failure) {
            // Do not expose loader errors, native messages, paths or account names.
            throw new IOException("Windows private ACL unavailable");
        }
    }

    // Called only after the pinned native dispatch has actually loaded. Process lifetime pins
    // replace exact ancestor ACE policy inside the OS Known Folder development boundary.
    static void holdDevelopmentProfile(Path root) throws IOException { NativeAcl.holdDevelopmentProfile(root); }

    /** Only numeric Win32 codes may cross the credential diagnostic boundary. */
    static final class NativeFailure extends IOException {
        final int code;
        NativeFailure(int code) { super("Windows private ACL operation failed"); this.code = code; }
    }

    private static final class NativeAcl {
        private static final Kernel32 KERNEL = Kernel32.INSTANCE;
        private static final Advapi32 SECURITY = Advapi32.INSTANCE;
        private static final ExtraKernel PATHS = Native.load("kernel32", ExtraKernel.class);
        private static final ExtraSecurity DESCRIPTORS = Native.load("advapi32", ExtraSecurity.class);
        private static final int READ_CONTROL = 0x20000, READ_ATTRIBUTES = 0x80, LIST_DIRECTORY = 0x1;
        private static final int FULL_CONTROL = 0x1f01ff;
        private static Pins profilePins;
        private static Path developmentRoot;

        static void holdDevelopmentProfile(Path root) throws IOException {
            if (profilePins != null) throw new IOException("Development Profile already bound");
            var pins = new Pins();
            try {
                Path part = root.getRoot();
                pins.add(open(part, true, true));
                for (Path name : root) {
                    part = part.resolve(name);
                    HANDLE handle = open(part, true, true);
                    pins.add(handle);
                    if (part.equals(root) || part.equals(root.getParent()) || part.equals(root.getParent().getParent()))
                        privateAcl(handle, currentSid(), true, false);
                }
                developmentRoot = root; profilePins = pins;
            } catch (IOException | RuntimeException | Error failure) { pins.close(); throw failure; }
        }

        private static boolean pinnedAncestor(Path part, Path target) {
            return developmentRoot != null && target.startsWith(developmentRoot) && developmentRoot.startsWith(part);
        }

        private static void verifyAncestor(HANDLE handle, Path part, Path target, PSID user) throws IOException {
            if (!pinnedAncestor(part, target)) ancestorAcl(handle, user);
            else if (part.startsWith(developmentRoot.getParent().getParent()))
                privateAcl(handle, user, true, false); // Recheck the private boundary after admission, too.
        }
        // Ancestors may belong to OS/administrative identities, which are already outside the account boundary.
        // Private objects use the current-user/system/admin policy below.
        private static final Set<String> SYSTEM_OWNERS = Set.of("S-1-5-18", "S-1-5-32-544",
                "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464"); // TrustedInstaller service SID

        // Exact SDK signatures for the three calls absent/inconvenient in JNA-platform 5.17.0.
        interface ExtraKernel extends StdCallLibrary {
            int GetFinalPathNameByHandleW(HANDLE file, char[] buffer, int length, int flags);
        }
        interface ExtraSecurity extends StdCallLibrary {
            boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(WString text, int revision,
                    PointerByReference descriptor, IntByReference size);
            boolean GetSecurityDescriptorControl(Pointer descriptor, ShortByReference control, IntByReference revision);
        }

        static void operate(Path input, boolean directory, boolean create, boolean sidecar) throws IOException {
            Path path = input.toAbsolutePath().normalize();
            if (path.getFileSystem() != FileSystems.getDefault() || !path.toString().matches("[A-Za-z]:\\\\.*")
                    || path.getParent() == null) throw new IOException("Local Windows object required");
            for (Path name : path) {
                String part = name.toString();
                if (part.contains(":") || part.endsWith(".") || part.endsWith(" "))
                    throw new IOException("Invalid Windows object name");
            }
            PSID user = currentSid();
            // LIST_DIRECTORY plus no SHARE_DELETE pins opened directories only until this operation returns.
            // File handles below are metadata reads, not a pin for later Java I/O.
            try (Pins pins = new Pins()) {
                Path part = path.getRoot();
                HANDLE root = open(part, true, true);
                pins.add(root);
                if (sidecar && part.equals(path.getParent())) privateAcl(root, user, true, false);
                else verifyAncestor(root, part, path, user);
                for (Path name : path) {
                    part = part.resolve(name);
                    boolean last = part.equals(path), isDirectory = !last || directory;
                    HANDLE handle;
                    if (last && create && !directory) {
                        // CREATE_NEW never opens/truncates an existing object.
                        try (Descriptor descriptor = new Descriptor(user, false)) {
                            handle = KERNEL.CreateFile(nativePath(part), READ_CONTROL | READ_ATTRIBUTES, 3,
                                    descriptor.attributes(), 1, 0x00200000, null);
                            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                                int error = KERNEL.GetLastError();
                                if (error == 80 || error == 183) throw new FileAlreadyExistsException("Private object");
                                throw new NativeFailure(error);
                            }
                            pins.add(handle);
                        }
                        identity(handle, part, false);
                    } else {
                        try { handle = open(part, isDirectory, true); }
                        catch (NativeFailure absent) {
                            if (!create || !isDirectory || absent.code != 2 && absent.code != 3) throw absent;
                            try (Descriptor descriptor = new Descriptor(user, true)) {
                                if (!KERNEL.CreateDirectory(nativePath(part), descriptor.attributes()))
                                    throw new NativeFailure(KERNEL.GetLastError());
                            }
                            handle = open(part, true, true);
                            pins.add(handle);
                            privateAcl(handle, user, true, false);
                            continue;
                        }
                        pins.add(handle);
                    }
                    if (last) privateAcl(handle, user, directory, sidecar);
                    else if (sidecar && part.equals(path.getParent())) privateAcl(handle, user, true, false);
                    else verifyAncestor(handle, part, path, user);
                }
            }
        }

        private static String nativePath(Path path) { return "\\\\?\\" + path; }

        private static HANDLE open(Path path, boolean directory, boolean security) throws IOException {
            HANDLE handle = KERNEL.CreateFile(nativePath(path), READ_ATTRIBUTES | (security ? READ_CONTROL : 0)
                    | (directory ? LIST_DIRECTORY : 0),
                    3, null, 3, 0x02200000, null); // SHARE_READ|WRITE, OPEN_EXISTING, BACKUP_SEMANTICS|OPEN_REPARSE_POINT
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new NativeFailure(KERNEL.GetLastError());
            try { identity(handle, path, directory); return handle; }
            catch (IOException | RuntimeException | Error failure) { close(handle); throw failure; }
        }

        private static void identity(HANDLE handle, Path path, boolean directory) throws IOException {
            var info = new WinBase.FILE_ATTRIBUTE_TAG_INFO();
            if (!KERNEL.GetFileInformationByHandleEx(handle, 9, info.getPointer(), new WinDef.DWORD(info.size())))
                throw new NativeFailure(KERNEL.GetLastError());
            info.read();
            if ((info.FileAttributes & 0x400) != 0 || ((info.FileAttributes & 0x10) != 0) != directory
                    || KERNEL.GetFileType(handle) != 1) throw new IOException("Windows object type rejected");
            char[] finalPath = new char[32768];
            int length = PATHS.GetFinalPathNameByHandleW(handle, finalPath, finalPath.length, 0);
            if (length == 0) throw new NativeFailure(KERNEL.GetLastError());
            if (length >= finalPath.length || !new String(finalPath, 0, length).equalsIgnoreCase(nativePath(path)))
                throw new IOException("Windows object identity rejected");
            // Require a real disk identity, rather than Java ACL-list/path equality.
            var id = new WinBase.FILE_ID_INFO();
            if (!KERNEL.GetFileInformationByHandleEx(handle, 18, id.getPointer(), new WinDef.DWORD(id.size())))
                throw new NativeFailure(KERNEL.GetLastError());
        }

        private static PSID currentSid() throws IOException {
            HANDLEByReference token = new HANDLEByReference();
            if (!SECURITY.OpenThreadToken(KERNEL.GetCurrentThread(), 8, true, token)) {
                int error = KERNEL.GetLastError();
                if (error != 1008) throw new NativeFailure(error); // Only ERROR_NO_TOKEN permits the process token.
                if (!SECURITY.OpenProcessToken(KERNEL.GetCurrentProcess(), 8, token))
                    throw new NativeFailure(KERNEL.GetLastError());
            }
            try {
                TOKEN_USER user = new TOKEN_USER(1024); // TOKEN_USER plus the maximum SID fits this bounded buffer.
                if (!SECURITY.GetTokenInformation(token.getValue(), 1, user, 1024, new IntByReference()))
                    throw new NativeFailure(KERNEL.GetLastError());
                if (!SECURITY.IsValidSid(user.User.Sid)) throw new IOException("Current Windows SID unavailable");
                return new PSID(user.User.Sid.getBytes());
            } finally { close(token.getValue()); }
        }

        private static void privateAcl(HANDLE handle, PSID user, boolean directory, boolean sidecar) throws IOException {
            PointerByReference owner = new PointerByReference(), dacl = new PointerByReference(), sd = new PointerByReference();
            int error = SECURITY.GetSecurityInfo(handle, 1, 5, owner, null, dacl, null, sd); // FILE_OBJECT; OWNER|DACL only
            if (error != 0) throw new NativeFailure(error);
            try {
                ShortByReference control = new ShortByReference();
                if (!DESCRIPTORS.GetSecurityDescriptorControl(sd.getValue(), control, new IntByReference()))
                    throw new NativeFailure(KERNEL.GetLastError());
                if (owner.getValue() == null || !SECURITY.IsValidSid(new PSID(owner.getValue()))
                        || !SECURITY.EqualSid(user, new PSID(owner.getValue())))
                    throw new IOException("Windows private owner rejected");
                boolean inheritedSidecar = sidecar && (control.getValue() & 0x1000) == 0;
                if ((control.getValue() & 4) == 0 || (!inheritedSidecar && (control.getValue() & 0x1000) == 0)
                        || dacl.getValue() == null)
                    throw new IOException("Windows private protection rejected");
                // JNA's maintained typed ACL/ACE structures own descriptor decoding.
                // Do not parse ACE offsets or require one exact owner-only ACE.
                boolean full = false;
                for (ACE_HEADER entry : new ACL(dacl.getValue()).getACEs()) {
                    if (entry instanceof ACCESS_DENIED_ACE) continue;
                    if (!(entry instanceof ACCESS_ALLOWED_ACE allow)) throw new IOException("Windows private ACE rejected");
                    PSID sid = allow.getSID();
                    if (!SECURITY.IsValidSid(sid)) throw new IOException("Windows private SID rejected");
                    boolean current = SECURITY.EqualSid(user, sid);
                    String value = allow.getSidString();
                    boolean trusted = current || value.equals("S-1-5-18") || value.equals("S-1-5-32-544");
                    // Read attributes/ACL, synchronization and traversal disclose no file contents.
                    int harmless = 0x120080 | (directory ? 0x20 : 0);
                    if (!trusted && (allow.Mask & ~harmless) != 0) throw new IOException("Windows private access rejected");
                    if (current && (allow.AceFlags & 8) == 0 && (allow.Mask & FULL_CONTROL) == FULL_CONTROL) full = true;
                }
                if (!full) throw new IOException("Windows private access unavailable");
            } finally { free(sd.getValue()); }
        }

        private static boolean trustedAncestorSid(PSID sid, PSID user) {
            return SECURITY.IsValidSid(sid) && (SECURITY.EqualSid(user, sid)
                    || SYSTEM_OWNERS.contains(Advapi32Util.convertSidToStringSid(sid)));
        }

        private static void ancestorAcl(HANDLE handle, PSID user) throws IOException {
            PointerByReference owner = new PointerByReference(), dacl = new PointerByReference(), sd = new PointerByReference();
            int error = SECURITY.GetSecurityInfo(handle, 1, 5, owner, null, dacl, null, sd);
            if (error != 0) throw new NativeFailure(error);
            try {
                if (owner.getValue() == null || dacl.getValue() == null
                        || !trustedAncestorSid(new PSID(owner.getValue()), user))
                    throw new IOException("Windows ancestor authority rejected");
                for (ACE_HEADER entry : new ACL(dacl.getValue()).getACEs()) {
                    if (entry instanceof ACCESS_DENIED_ACE || (entry.AceFlags & 8) != 0) continue;
                    if (!(entry instanceof ACCESS_ALLOWED_ACE allow)) throw new IOException("Windows ancestor ACE unsupported");
                    if ((allow.Mask & 0x500d0040) != 0 && !trustedAncestorSid(allow.getSID(), user))
                        throw new IOException("Windows ancestor replacement authority rejected");
                }
            } finally { free(sd.getValue()); }
        }

        private static final class Descriptor implements AutoCloseable {
            private final Pointer pointer;
            Descriptor(PSID user, boolean directory) throws IOException {
                String sid = Advapi32Util.convertSidToStringSid(user);
                PointerByReference value = new PointerByReference();
                String sddl = "O:" + sid + "D:P(A;" + (directory ? "OICI" : "") + ";FA;;;" + sid + ")";
                if (!DESCRIPTORS.ConvertStringSecurityDescriptorToSecurityDescriptorW(new WString(sddl), 1, value, null))
                    throw new NativeFailure(KERNEL.GetLastError());
                pointer = value.getValue();
                if (pointer == null) throw new IOException("Windows private descriptor unavailable");
            }
            WinBase.SECURITY_ATTRIBUTES attributes() {
                var attributes = new WinBase.SECURITY_ATTRIBUTES();
                attributes.lpSecurityDescriptor = pointer;
                attributes.bInheritHandle = false;
                return attributes;
            }
            @Override public void close() throws IOException { free(pointer); }
        }

        private static void close(HANDLE handle) throws IOException {
            if (!KERNEL.CloseHandle(handle)) throw new NativeFailure(KERNEL.GetLastError());
        }
        private static void free(Pointer pointer) throws IOException {
            if (pointer != null && KERNEL.LocalFree(pointer) != null) throw new NativeFailure(KERNEL.GetLastError());
        }
        private static final class Pins implements AutoCloseable {
            private final List<HANDLE> handles = new ArrayList<>();
            void add(HANDLE handle) { handles.add(handle); }
            @Override public void close() throws IOException {
                IOException failure = null;
                for (int i = handles.size() - 1; i >= 0; i--) {
                    try { NativeAcl.close(handles.get(i)); }
                    catch (IOException error) { if (failure == null) failure = error; }
                }
                if (failure != null) throw failure;
            }
        }
    }
}
