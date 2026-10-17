using System;
using System.Buffers.Binary;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using System.Windows.Media;
using Microsoft.Win32;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed record PickedVisionImage(byte[] Bytes,string MediaType,int Width,int Height,BitmapSource Preview)
{ public override string ToString()=>"PickedVisionImage[redacted]"; }
internal interface IVisionImages
{
    Task<PickedVisionImage?> PickAsync(CancellationToken token);
    void ShowPreview(PickedVisionImage image);
    void ClearPreview();
}
internal sealed class NativeVisionImages(Func<Window?> owner,Action? closed=null,Func<NativeVisionImages.SafeImage,IVisionPreviewCodec>? codecs=null):IVisionImages
{
    private int worker; private Window? preview;
    public async Task<PickedVisionImage?> PickAsync(CancellationToken token)
    {
        if(Volatile.Read(ref worker)!=0)throw new DesktopException(DesktopError.QueueFull);
        token.ThrowIfCancellationRequested();var host=owner()??throw new DesktopException(DesktopError.RuntimeUnavailable);
        var picker=new OpenFileDialog {Title="选择一张 PNG / JPEG",Filter="PNG / JPEG|*.png;*.jpg;*.jpeg",Multiselect=false,CheckFileExists=true};
        if(picker.ShowDialog(host)!=true)return null;
        token.ThrowIfCancellationRequested();
        using var file=NativeKnowledgeSourceFiles.OpenVisionImage(picker.FileName);
        return await ReadSelectedAsync(file,token);
    }
    // Processes an already selected, bounded stream. Production callers own the validated file
    // handle above; no path, image bytes or codec selection is exposed through the bridge.
    internal async Task<PickedVisionImage> ReadSelectedAsync(Stream file,CancellationToken token)
    {
        if(Interlocked.CompareExchange(ref worker,1,0)!=0)throw new DesktopException(DesktopError.QueueFull);
        try {
            return await Task.Run(async ()=> {
                long started=System.Diagnostics.Stopwatch.GetTimestamp();
                void Check() {token.ThrowIfCancellationRequested();if(System.Diagnostics.Stopwatch.GetElapsedTime(started)>=TimeSpan.FromSeconds(5))throw new DesktopException(DesktopError.ClientTimeout);}
                Check();if(file.Length is <=0 or >4*1024*1024)throw new DesktopException(DesktopError.InvalidRequest);
                byte[] bytes=new byte[file.Length];int at=0;
                while(at<bytes.Length) {int count=await file.ReadAsync(bytes.AsMemory(at),token);if(count==0)throw new DesktopException(DesktopError.InvalidRequest);at+=count;Check();}
                if(file.ReadByte()!=-1)throw new DesktopException(DesktopError.InvalidRequest);
                var safe=Inspect(bytes);Check();
                var codec=codecs is null?new VisionWicCodec(safe):codecs(safe);
                bool completed=false;
                PickedVisionImage result;
                try {
                    if(codec is null || codec.Identity!=VisionWicCodec.Expected(safe.Media))throw new DesktopException(DesktopError.InvalidRequest);
                    var raster=codec.Decode(safe,Check);Check(); // After CopyPixels, including injected failures/delays.
                    if(raster.Width is <=0 or >512 || raster.Height is <=0 or >512 || raster.Pixels.Length!=raster.Width*raster.Height*4)throw new DesktopException(DesktopError.InvalidRequest);
                    var detached=BitmapSource.Create(raster.Width,raster.Height,96,96,PixelFormats.Bgra32,null,raster.Pixels,raster.Width*4);detached.Freeze();
                    Check(); // After detached raster creation; cleanup still precedes publication.
                    result=new PickedVisionImage(bytes,safe.Media,safe.Width,safe.Height,detached);
                    completed=true;
                } finally {try {codec?.Dispose();}catch when(!completed) {}}
                Check(); // Cleanup may itself cross the deadline or observe cancellation.
                return result;
            }); // Await the real worker; cancellation/deadline never releases a still-running codec.
        } catch(DesktopException) {throw;}catch(OperationCanceledException) {throw;}
        catch(Exception) {throw new DesktopException(DesktopError.InvalidRequest);}
        finally {Volatile.Write(ref worker,0);}
    }
    public void ShowPreview(PickedVisionImage image)
    {
        ClearPreview(); var panel=new StackPanel {Margin=new Thickness(16)};
        panel.Children.Add(new TextBlock {Text=$"{image.Width} × {image.Height} · 检查像素方向；不应用 EXIF 旋转或外部颜色配置。",TextWrapping=TextWrapping.Wrap});
        panel.Children.Add(new Image {Source=image.Preview,MaxWidth=512,MaxHeight=512,Margin=new Thickness(0,12,0,0)});
        preview=new Window {Owner=owner(),Title="Vision · 原生预览",Content=panel,Width=560,SizeToContent=SizeToContent.Height,MaxHeight=650};
        var shown=preview;shown.Closed+=(_,_)=>{if(ReferenceEquals(preview,shown)){preview=null;closed?.Invoke();}};preview.Show();
    }
    public void ClearPreview() {if(preview is {} old) {preview=null;old.Close();}}
    internal sealed record SafeImage(byte[] Bytes,string Media,int Width,int Height);
    internal static SafeImage Inspect(byte[] b)
    {
        static void Require(bool valid) {if(!valid) throw new DesktopException(DesktopError.InvalidRequest);}
        Require(b.Length is >0 and <=4*1024*1024);int w=0,h=0,p=0;bool png=b.Length>=8&&b.AsSpan(0,8).SequenceEqual(new byte[]{137,80,78,71,13,10,26,10});
        using var filtered=new MemoryStream(b.Length);
        if(png) {
            filtered.Write(b,0,8);p=8;bool data=false,ended=false,palette=false,alpha=false;int color=-1;
            while(p<b.Length) {
                Require(b.Length-p>=12);uint raw=BinaryPrimitives.ReadUInt32BigEndian(b.AsSpan(p));Require(raw<=b.Length-p-12);int n=(int)raw;
                string type=System.Text.Encoding.ASCII.GetString(b,p+4,4);Require(type.All(char.IsAsciiLetter)&&char.IsUpper(type[2])&&type is not ("acTL" or "fcTL" or "fdAT"));
                uint crc=0xffffffff;for(int i=p+4;i<p+8+n;i++) {crc^=b[i];for(int j=0;j<8;j++) crc=(crc>>1)^((crc&1)!=0?0xedb88320u:0);}
                Require((crc^0xffffffff)==BinaryPrimitives.ReadUInt32BigEndian(b.AsSpan(p+8+n)));
                Require(w!=0||type=="IHDR");bool keep=true;
                switch(type) {
                    case "IHDR":Require(p==8&&n==13);w=checked((int)BinaryPrimitives.ReadUInt32BigEndian(b.AsSpan(p+8)));h=checked((int)BinaryPrimitives.ReadUInt32BigEndian(b.AsSpan(p+12)));color=b[p+17];
                        Require(color is 0 or 2 or 3 or 4 or 6 && b[p+18]==0&&b[p+19]==0&&b[p+20]<=1);break;
                    case "PLTE":Require(!palette&&!data&&!alpha&&n>0&&n<=768&&n%3==0);palette=true;break;
                    case "tRNS":Require(!alpha&&!data&&color is not (4 or 6));alpha=true;break;
                    case "IDAT":Require(!ended&&(color!=3||palette));data=true;break;
                    case "IEND":Require(data&&n==0&&p+12==b.Length);filtered.Write(b,p,12);p+=12;continue;
                    default:Require(char.IsLower(type[0]));keep=false;break;
                }
                if(data&&type!="IDAT") ended=true;if(keep) filtered.Write(b,p,n+12);p+=n+12;
            }
            Require(data&&b.AsSpan(b.Length-8,4).SequenceEqual("IEND"u8));
        } else {
            Require(b.Length>=4&&b[0]==255&&b[1]==216);filtered.Write(b,0,2);p=2;bool scan=false,end=false;
            while(p<b.Length) {
                int start=p;Require(b[p++]==255);while(p<b.Length&&b[p]==255)p++;Require(p<b.Length);int marker=b[p++];
                if(marker==217) {Require(scan&&p==b.Length);filtered.Write(b,start,p-start);end=true;break;}
                Require(marker is not (0 or 1 or 216 or 220)&&!(marker>=208&&marker<=215)&&p+2<=b.Length);
                int n=BinaryPrimitives.ReadUInt16BigEndian(b.AsSpan(p));Require(n>=2&&n<=b.Length-p);
                if(marker>=192&&marker<=207&&marker is not (196 or 200 or 204)) {
                    Require(marker is 192 or 194 && w==0 && n>=8 && b[p+2]==8);h=BinaryPrimitives.ReadUInt16BigEndian(b.AsSpan(p+3));w=BinaryPrimitives.ReadUInt16BigEndian(b.AsSpan(p+5));int c=b[p+7];Require(c is 1 or 3 && n==8+3*c);for(int i=0;i<c;i++)Require(b[p+8+3*i]==i+1);
                }
                bool metadata=marker>=224&&marker<=239||marker==254;Require(metadata||marker is 192 or 194 or 196 or 219 or 221 or 218);
                if(marker==218)Require(w>0&&n>=6&&n==6+2*b[p+2]);if(!metadata)filtered.Write(b,start,p+n-start);p+=n;
                if(marker==218) {scan=true;int startData=p;while(p<b.Length) {if(b[p]!=255){p++;continue;}Require(p+1<b.Length);if(b[p+1]==0||b[p+1]>=208&&b[p+1]<=215){p+=2;continue;}break;}filtered.Write(b,startData,p-startData);}
            }
            Require(end);
        }
        Require(w>0&&h>0&&w<=4096&&h<=4096&&(long)w*h<=8_000_000);
        return new(filtered.ToArray(),png?"image/png":"image/jpeg",w,h);
    }
}
