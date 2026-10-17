using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed record VisionRaster(byte[] Pixels,int Width,int Height);
internal interface IVisionPreviewCodec:IDisposable
{
    Guid Identity {get;}
    VisionRaster Decode(NativeVisionImages.SafeImage image,Action checkpoint);
}

// No WPF stream decoder or WIC decoder discovery. Instantiate exactly one codec CLSID,
// verify its component CLSID before pixel decoding, and scale/convert that same frame.
internal sealed class VisionWicCodec:IVisionPreviewCodec
{
    // Current Windows SDK maps CLSID_WICPngDecoder to this Windows 8+ built-in
    // decoder. Pin Decoder2 explicitly; never retry Decoder1 or an installed plugin.
    internal static readonly Guid Png=new("e018945b-aa86-4008-9bd4-6777a1e40c11");
    internal static readonly Guid Jpeg=new("9456a480-e88b-43ea-9e73-0b2d9b71b1ca");
    private Decoder? decoder;
    private Factory? factory;
    private IStream? stream;
    private bool initialized;
    private readonly int ownerThread=Environment.CurrentManagedThreadId;
    internal static Guid Expected(string media)=>media switch {"image/png"=>Png,"image/jpeg"=>Jpeg,_=>throw new DesktopException(DesktopError.InvalidRequest)};
    internal VisionWicCodec(NativeVisionImages.SafeImage image)
    {
        Guid expected=Expected(image.Media);
        int apartment=CoInitializeEx(IntPtr.Zero,0);
        if(apartment>=0)initialized=true;
        else if(apartment!=unchecked((int)0x80010106))Marshal.ThrowExceptionForHR(apartment);
        ComponentInfo? component=null,actualInfo=null;
        bool completed=false;
        try {
            factory=Create<Factory>(new Guid("317d06e8-5f24-433d-bdf7-79ce68d8abc2"));
            factory.CreateComponentInfo(ref expected,out component);
            // This cast performs QI for IID_IWICBitmapDecoderInfo. It is an interface
            // alias of the component RCW; component owns that RCW, not two owners.
            var decoderInfo=(DecoderInfo)component;
            component.GetCLSID(out Guid componentClsid);
            if(componentClsid!=expected)throw new DesktopException(DesktopError.InvalidRequest);
            decoderInfo.CreateInstance(out decoder);
            decoder.GetDecoderInfo(out actualInfo);actualInfo.GetCLSID(out Guid actual);
            if(actual!=expected)throw new DesktopException(DesktopError.InvalidRequest);
            Identity=actual; // Both identities verified before stream initialization or pixels.
            stream=SHCreateMemStream(image.Bytes,(uint)image.Bytes.Length)??throw new DesktopException(DesktopError.InvalidRequest);
            decoder.Initialize(stream,0);
            completed=true;
        } finally {
            try {Release(actualInfo,component);}
            catch when(!completed) {} // Cleanup must not replace the original security failure.
            catch {completed=false;throw;}
            finally {if(!completed)try {Dispose();}catch {}}
        }
    }
    public Guid Identity {get;}
    public VisionRaster Decode(NativeVisionImages.SafeImage image,Action checkpoint)
    {
        CheckThread();
        Source? frame=null;Scaler? scaler=null;Converter? converter=null;
        bool completed=false;
        try {
            checkpoint();decoder!.GetFrameCount(out uint count);
            if(count!=1)throw new DesktopException(DesktopError.InvalidRequest);
            decoder.GetFrame(0,out frame);frame.GetSize(out uint w,out uint h);
            if(w!=image.Width || h!=image.Height)throw new DesktopException(DesktopError.InvalidRequest);
            int longest=Math.Max(image.Width,image.Height);
            uint width=(uint)Math.Max(1,(long)image.Width*Math.Min(512,longest)/longest);
            uint height=(uint)Math.Max(1,(long)image.Height*Math.Min(512,longest)/longest);
            factory!.CreateBitmapScaler(out scaler);scaler.Initialize(frame,width,height,1);
            // Numeric pixel conversion only: never create a color transformer or read ICC/EXIF.
            converter=Create<Converter>(new Guid("1a3f11dc-b514-4b17-8c5f-2154513852f1"));
            Guid bgra=new("6fddc324-4e03-4bfe-b185-3d77768dc90f");
            converter.Initialize((Source)scaler,ref bgra,0,IntPtr.Zero,0,0);
            byte[] pixels=new byte[checked((int)(width*height*4))];
            converter.CopyPixels(IntPtr.Zero,width*4,(uint)pixels.Length,pixels);
            checkpoint(); // Includes all synchronous codec work, particularly CopyPixels.
            completed=true;
            return new(pixels,(int)width,(int)height);
        } finally {try {Release(converter,scaler,frame);}catch when(!completed) {}}
    }
    private void CheckThread() {if(Environment.CurrentManagedThreadId!=ownerThread)throw new InvalidOperationException("WIC codec belongs to its creating worker.");}
    public void Dispose()
    {
        CheckThread();var ownedDecoder=decoder;var ownedStream=stream;var ownedFactory=factory;
        decoder=null;stream=null;factory=null;
        try {Release(ownedDecoder,ownedStream,ownedFactory);}
        finally {if(initialized){initialized=false;CoUninitialize();}}
    }
    private static void Release(params object?[] owners)
    {
        Exception? failure=null;
        for(int i=0;i<owners.Length;i++) {
            object? value=owners[i];if(value is null || !Marshal.IsComObject(value))continue;
            bool alias=false;for(int n=0;n<i;n++)if(ReferenceEquals(value,owners[n])){alias=true;break;}
            if(alias)continue; // GetDecoderInfo may return the same native identity/RCW.
            try {Marshal.FinalReleaseComObject(value);}catch(Exception error){failure??=error;}
        }
        if(failure is not null)throw failure; // All owned references were attempted first.
    }
    private static T Create<T>(Guid clsid) where T:class
    {
        Guid iid=typeof(T).GUID;
        Marshal.ThrowExceptionForHR(CoCreateInstance(ref clsid,IntPtr.Zero,1,ref iid,out object instance));
        try {return (T)instance;}catch {try {Release(instance);}catch {}throw;}
    }
    [DllImport("ole32.dll",ExactSpelling=true)]private static extern int CoCreateInstance(ref Guid clsid,IntPtr outer,uint context,ref Guid iid,[MarshalAs(UnmanagedType.Interface)]out object instance);
    [DllImport("ole32.dll",ExactSpelling=true)]private static extern int CoInitializeEx(IntPtr reserved,uint options);
    [DllImport("ole32.dll",ExactSpelling=true)]private static extern void CoUninitialize();
    [DllImport("shlwapi.dll",ExactSpelling=true)][return:MarshalAs(UnmanagedType.Interface)]private static extern IStream? SHCreateMemStream(byte[] bytes,uint count);

    // Prefixes below retain SDK vtable order; unused entries are never called.
    [ComImport,Guid("9edde9e7-8dee-47ea-99df-e6faf2ed44bf"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]private interface Decoder
    {
        void QueryCapability(IStream stream,out uint capability);
        void Initialize(IStream stream,uint options);
        void GetContainerFormat(out Guid format);
        void GetDecoderInfo(out ComponentInfo info);
        void CopyPalette(IntPtr palette);
        void GetMetadataQueryReader(out IntPtr reader);
        void GetPreview(out Source preview);
        void GetColorContexts(uint count,IntPtr contexts,out uint actual);
        void GetThumbnail(out Source thumbnail);
        void GetFrameCount(out uint count);
        void GetFrame(uint index,out Source frame);
    }
    [ComImport,Guid("23bc3f0a-698b-4357-886b-f24d50671334"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]private interface ComponentInfo
    {void GetComponentType(out uint type);void GetCLSID(out Guid clsid);}
    [ComImport,Guid("d8cd007f-d08f-4191-9bfc-236ea7f0e4b5"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]private interface DecoderInfo
    {
        // Flattened SDK inheritance: IWICComponentInfo (8), IWICBitmapCodecInfo (12),
        // then IWICBitmapDecoderInfo (3). CreateInstance is IUnknown vtable slot 25.
        void GetComponentType();void GetCLSID();void GetSigningStatus();void GetAuthor();
        void GetVendorGUID();void GetVersion();void GetSpecVersion();void GetFriendlyName();
        void GetContainerFormat();void GetPixelFormats();void GetColorManagementVersion();
        void GetDeviceManufacturer();void GetDeviceModels();void GetMimeTypes();void GetFileExtensions();
        void DoesSupportAnimation();void DoesSupportChromakey();void DoesSupportLossless();void DoesSupportMultiframe();void MatchesMimeType();
        void GetPatterns();void MatchesPattern();void CreateInstance(out Decoder decoder);
    }
    [ComImport,Guid("00000120-a8f2-4877-ba0a-fd2b6645fb94"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]private interface Source
    {
        void GetSize(out uint width,out uint height);void GetPixelFormat(out Guid format);void GetResolution(out double x,out double y);
        void CopyPalette(IntPtr palette);void CopyPixels(IntPtr rect,uint stride,uint size,[Out,MarshalAs(UnmanagedType.LPArray,SizeParamIndex=2)]byte[] pixels);
    }
    [ComImport,Guid("00000302-a8f2-4877-ba0a-fd2b6645fb94"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]private interface Scaler
    {
        void GetSize(out uint width,out uint height);void GetPixelFormat(out Guid format);void GetResolution(out double x,out double y);
        void CopyPalette(IntPtr palette);void CopyPixels(IntPtr rect,uint stride,uint size,[Out,MarshalAs(UnmanagedType.LPArray,SizeParamIndex=2)]byte[] pixels);
        void Initialize(Source source,uint width,uint height,uint interpolation);
    }
    [ComImport,Guid("00000301-a8f2-4877-ba0a-fd2b6645fb94"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]private interface Converter
    {
        void GetSize(out uint width,out uint height);void GetPixelFormat(out Guid format);void GetResolution(out double x,out double y);
        void CopyPalette(IntPtr palette);void CopyPixels(IntPtr rect,uint stride,uint size,[Out,MarshalAs(UnmanagedType.LPArray,SizeParamIndex=2)]byte[] pixels);
        void Initialize(Source source,ref Guid format,uint dither,IntPtr palette,double threshold,uint paletteType);
    }
    [ComImport,Guid("ec5ec8a9-c395-4314-9c77-54d7a935ff70"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]private interface Factory
    {
        void CreateDecoderFromFilename();void CreateDecoderFromStream();void CreateDecoderFromFileHandle();void CreateComponentInfo(ref Guid clsid,out ComponentInfo info);
        void CreateDecoder();void CreateEncoder();void CreatePalette();void CreateFormatConverter();
        void CreateBitmapScaler(out Scaler scaler);
    }
}
