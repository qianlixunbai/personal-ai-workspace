using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed class KnowledgeSourceFile(Stream stream,string filename):IDisposable
{
    internal Stream Stream {get;}=stream;
    internal string Filename {get;}=filename;
    public override string ToString()=>"KnowledgeSourceFile[redacted]";
    public void Dispose()=>Stream.Dispose();
}
internal interface IKnowledgeSourceFiles { Task<KnowledgeSourceFile?> PickAsync(CancellationToken token); }
internal sealed class NativeKnowledgeSourceFiles(Func<Window?> owner):IKnowledgeSourceFiles
{
    public Task<KnowledgeSourceFile?> PickAsync(CancellationToken token)
    {
        token.ThrowIfCancellationRequested();var window=owner()??throw new DesktopException(DesktopError.KnowledgeFileUnavailable);
        var picker=new OpenFileDialog{Title="导入 Knowledge · 严格 UTF-8 TXT / Markdown",Filter="TXT / Markdown (*.txt;*.md;*.markdown)|*.txt;*.md;*.markdown",Multiselect=false,CheckFileExists=true};
        bool selected=picker.ShowDialog(window)==true;token.ThrowIfCancellationRequested();window.Activate();
        return Task.FromResult(selected?Open(picker.FileName):null);
    }
    internal static KnowledgeSourceFile Open(string path)
    {
        SafeFileHandle? handle=null;FileStream? file=null;
        try {
            LocalPath(path);string filename=Path.GetFileName(path);RuntimeClient.ValidateKnowledgeFilename(filename);
            // OPEN_REPARSE_POINT binds validation to the object actually opened, never a Runtime path reopen.
            handle=CreateFile(path,0x80000000,1,IntPtr.Zero,3,0x00200000|0x08000000|0x40000000,IntPtr.Zero);
            if(handle.IsInvalid||GetFileType(handle)!=1||!GetFileInformationByHandleEx(handle,9,out var info,(uint)Marshal.SizeOf<AttributeTag>())
                ||(info.Attributes&((uint)FileAttributes.ReparsePoint|(uint)FileAttributes.Directory))!=0)throw new IOException();
            var resolved=new StringBuilder(32768);uint length=GetFinalPathNameByHandle(handle,resolved,(uint)resolved.Capacity,0);
            if(length==0||length>=resolved.Capacity)throw new IOException();string final=resolved.ToString();
            if(!final.StartsWith(@"\\?\",StringComparison.Ordinal)||final.StartsWith(@"\\?\UNC\",StringComparison.OrdinalIgnoreCase))throw new IOException();
            LocalPath(final[4..]);file=new FileStream(handle,FileAccess.Read,65536,true);handle=null;
            if(file.Length<=0)throw new DesktopException(DesktopError.KnowledgeInvalidSource);
            if(file.Length>RuntimeClient.MaximumKnowledgeSourceBytes)throw new DesktopException(DesktopError.KnowledgeSourceTooLarge);
            var result=new KnowledgeSourceFile(file,filename);file=null;return result;
        }catch(DesktopException){throw;}catch(Exception){throw new DesktopException(DesktopError.KnowledgeInvalidSource);}
        finally{file?.Dispose();handle?.Dispose();}
    }
    private static void LocalPath(string path)
    {
        if(!Path.IsPathFullyQualified(path)||path.StartsWith(@"\\",StringComparison.Ordinal)||path.Contains('\0'))throw new IOException();
        if(new DriveInfo(Path.GetPathRoot(path)!).DriveType==DriveType.Network)throw new IOException();
        for(string? part=path;part is not null;part=Path.GetDirectoryName(part))
            if((File.GetAttributes(part)&FileAttributes.ReparsePoint)!=0)throw new IOException();
    }
    [StructLayout(LayoutKind.Sequential)]private struct AttributeTag {internal uint Attributes;internal uint Tag;}
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]private static extern SafeFileHandle CreateFile(string path,uint access,uint share,IntPtr security,uint creation,uint flags,IntPtr template);
    [DllImport("kernel32.dll",SetLastError=true)]private static extern uint GetFileType(SafeFileHandle file);
    [DllImport("kernel32.dll",SetLastError=true)]private static extern bool GetFileInformationByHandleEx(SafeFileHandle file,int kind,out AttributeTag info,uint size);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]private static extern uint GetFinalPathNameByHandle(SafeFileHandle file,StringBuilder path,uint size,uint flags);
}
