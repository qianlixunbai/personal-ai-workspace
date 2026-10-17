using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class VisionPreviewTests
{
    private sealed class Codec(Guid identity,Action<Action>? work=null,Action? cleanup=null):IVisionPreviewCodec
    {
        internal int Disposed,Decodes;
        public Guid Identity=>identity;
        public VisionRaster Decode(NativeVisionImages.SafeImage image,Action checkpoint) {Decodes++;work?.Invoke(checkpoint);checkpoint();return new(new byte[4],1,1);}
        public void Dispose() {Disposed++;cleanup?.Invoke();}
    }
    private static byte[] Image(bool jpeg=false)
    {
        var source=BitmapSource.Create(1024,768,96,96,PixelFormats.Gray16,null,new ushort[1024*768],2048);source.Freeze();
        BitmapEncoder encoder=jpeg?new JpegBitmapEncoder():new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(source));
        using var output=new MemoryStream();encoder.Save(output);return output.ToArray();
    }
    [Fact] public async Task ExplicitWindowsCodecsProduceFrozenPreview()
    {
        byte[] png=Image();
        Assert.Equal(new Guid("e018945b-aa86-4008-9bd4-6777a1e40c11"),VisionWicCodec.Png);
        Assert.Equal(new Guid("9456a480-e88b-43ea-9e73-0b2d9b71b1ca"),VisionWicCodec.Jpeg);
        foreach(byte[] bytes in new[]{png,Image(true)}) {
            var safe=NativeVisionImages.Inspect(bytes);
            // Constructor Initialize failure must release its partial native ownership.
            Assert.ThrowsAny<Exception>(()=>new VisionWicCodec(safe with {Bytes=Array.Empty<byte>()}));
            using(var codec=new VisionWicCodec(safe)) {
                Assert.Equal(VisionWicCodec.Expected(safe.Media),codec.Identity);
                int checkpoints=0;
                Assert.Throws<OperationCanceledException>(()=>codec.Decode(safe,()=>{if(++checkpoints==2)throw new OperationCanceledException();}));
                Assert.Equal(2,checkpoints); // Cancellation after real CopyPixels, with native cleanup.
                var raster=codec.Decode(safe,()=>{});
                Assert.Equal(512,raster.Width);Assert.Equal(384,raster.Height);Assert.Equal(512*384*4,raster.Pixels.Length);
            }
            var owner=new NativeVisionImages(()=>null);
            using var input=new MemoryStream(bytes,false);
            var result=await owner.ReadSelectedAsync(input,CancellationToken.None);
            Assert.True(result.Preview.IsFrozen);Assert.Equal(512,result.Preview.PixelWidth);Assert.Equal(384,result.Preview.PixelHeight);
        }
    }
    [Fact] public async Task FailClosedPreviewLifecycleKeepsWorkerUntilExit()
    {
        byte[] png=Image();
        var wrong=new Codec(Guid.Empty);int attempts=0;
        var rejected=new NativeVisionImages(()=>null,codecs:_=>{attempts++;return wrong;});
        using(var input=new MemoryStream(png,false))await Assert.ThrowsAsync<DesktopException>(()=>rejected.ReadSelectedAsync(input,CancellationToken.None));
        Assert.Equal(1,attempts);Assert.Equal(0,wrong.Decodes);Assert.Equal(1,wrong.Disposed);
        var missing=new NativeVisionImages(()=>null,codecs:_=>{attempts++;throw new DllNotFoundException("fixture");});
        using(var input=new MemoryStream(png,false))await Assert.ThrowsAsync<DesktopException>(()=>missing.ReadSelectedAsync(input,CancellationToken.None));
        Assert.Equal(2,attempts); // One attempt per request, no fallback factory.
        var primary=new DesktopException(DesktopError.RuntimeUnavailable);
        var broken=new Codec(VisionWicCodec.Png,_=>throw primary,()=>throw new InvalidOperationException("cleanup fixture"));
        var failed=new NativeVisionImages(()=>null,codecs:_=>broken);
        using(var input=new MemoryStream(png,false))Assert.Same(primary,await Assert.ThrowsAsync<DesktopException>(()=>failed.ReadSelectedAsync(input,CancellationToken.None)));
        Assert.Equal(1,broken.Disposed);
        using var entered=new ManualResetEventSlim();using var release=new ManualResetEventSlim();using var cancellation=new CancellationTokenSource();
        bool cleanupFails=true;
        var blocked=new Codec(VisionWicCodec.Png,_=>{entered.Set();release.Wait();},()=>{if(cleanupFails)throw new InvalidOperationException("cleanup fixture");});
        var draining=new NativeVisionImages(()=>null,codecs:_=>blocked);
        using var selected=new MemoryStream(png,false);var worker=draining.ReadSelectedAsync(selected,cancellation.Token);
        Assert.True(entered.Wait(TimeSpan.FromSeconds(3)));cancellation.Cancel();
        try {
            Assert.False(worker.IsCompleted);Assert.Equal(0,blocked.Disposed);
            using var next=new MemoryStream(png,false);
            Assert.Equal(DesktopError.QueueFull,(await Assert.ThrowsAsync<DesktopException>(()=>draining.ReadSelectedAsync(next,CancellationToken.None))).Error);
        } finally {release.Set();}
        await Assert.ThrowsAnyAsync<OperationCanceledException>(()=>worker);Assert.Equal(1,blocked.Disposed);
        cleanupFails=false; // Cleanup failure did not replace cancellation or release a live worker.
        // Slot is reusable only after the cancelled codec actually returned.
        using(var input=new MemoryStream(png,false))Assert.True((await draining.ReadSelectedAsync(input,CancellationToken.None)).Preview.IsFrozen);
        var badCleanup=new Codec(VisionWicCodec.Png,cleanup:()=>throw new InvalidOperationException("cleanup fixture"));
        var noSuccess=new NativeVisionImages(()=>null,codecs:_=>badCleanup);
        using(var input=new MemoryStream(png,false))await Assert.ThrowsAsync<DesktopException>(()=>noSuccess.ReadSelectedAsync(input,CancellationToken.None));
        Assert.Equal(1,badCleanup.Disposed);
        var lateCodec=new Codec(VisionWicCodec.Png,_=>Thread.Sleep(5100));
        var late=new NativeVisionImages(()=>null,codecs:_=>lateCodec);
        using(var input=new MemoryStream(png,false))Assert.Equal(DesktopError.ClientTimeout,(await Assert.ThrowsAsync<DesktopException>(()=>late.ReadSelectedAsync(input,CancellationToken.None))).Error);
        Assert.Equal(1,lateCodec.Disposed);
    }
    [Fact] public async Task CleanupMustFinishBeforeFinalCancellationAndDeadlineCheck()
    {
        byte[] png=Image();
        foreach(string outcome in new[]{"success","cancel","deadline"}) {
            var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            using var release=new ManualResetEventSlim();using var cancellation=new CancellationTokenSource();
            var codec=new Codec(VisionWicCodec.Png,cleanup:()=>{
                entered.TrySetResult();
                if(!release.Wait(TimeSpan.FromSeconds(15)))throw new TimeoutException("cleanup fixture");
            });
            var owner=new NativeVisionImages(()=>null,codecs:_=>codec);
            using var input=new MemoryStream(png,false);
            var pending=owner.ReadSelectedAsync(input,cancellation.Token);
            try {
                await entered.Task.WaitAsync(TimeSpan.FromSeconds(3));
                // The worker deadline started before cleanup; holding cleanup for at least
                // five seconds from here deterministically crosses it without a sleep race.
                long held=System.Diagnostics.Stopwatch.GetTimestamp();
                if(outcome=="cancel")cancellation.Cancel();
                Assert.False(pending.IsCompleted);Assert.Equal(1,codec.Disposed);
                using var next=new MemoryStream(png,false);
                Assert.Equal(DesktopError.QueueFull,(await Assert.ThrowsAsync<DesktopException>(()=>owner.ReadSelectedAsync(next,CancellationToken.None))).Error);
                if(outcome=="deadline") {
                    TimeSpan remaining;
                    while((remaining=TimeSpan.FromSeconds(5)-System.Diagnostics.Stopwatch.GetElapsedTime(held))>TimeSpan.Zero)
                        await Task.Delay(remaining);
                    Assert.False(pending.IsCompleted);
                }
            } finally {release.Set();}
            if(outcome=="cancel") {
                var error=await Assert.ThrowsAnyAsync<OperationCanceledException>(()=>pending);
                Assert.Equal(cancellation.Token,error.CancellationToken);
            } else if(outcome=="deadline") {
                Assert.Equal(DesktopError.ClientTimeout,(await Assert.ThrowsAsync<DesktopException>(()=>pending)).Error);
            } else Assert.True((await pending).Preview.IsFrozen);
            Assert.Equal(1,codec.Disposed);
            // Cancellation/timeout cannot return the slot until cleanup actually exits.
            using var reused=new MemoryStream(png,false);
            Assert.True((await owner.ReadSelectedAsync(reused,CancellationToken.None)).Preview.IsFrozen);
            Assert.Equal(2,codec.Disposed);
        }
    }
}
