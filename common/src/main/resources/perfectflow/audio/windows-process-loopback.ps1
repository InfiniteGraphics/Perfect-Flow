param(
    [Parameter(Mandatory = $true)]
    [int] $ProcessId,
    [Parameter(Mandatory = $true)]
    [string] $OutputRaw,
    [Parameter(Mandatory = $true)]
    [string] $OutputMeta
)

$source = @"
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace PerfectFlow.WindowsAudio
{
    public static class ProcessLoopbackHost
    {
        public static int Run(int processId, string outputRaw, string outputMeta)
        {
            ProcessLoopbackCapture capture = null;
            try
            {
                capture = new ProcessLoopbackCapture(processId, outputRaw, outputMeta);
                return capture.Run();
            }
            catch (Exception exception)
            {
                Console.Out.WriteLine("ERROR|" + exception.Message);
                Console.Out.Flush();
                return 1;
            }
            finally
            {
                if (capture != null)
                {
                    capture.Dispose();
                }
            }
        }
    }

    internal sealed class ProcessLoopbackCapture : IDisposable
    {
        private const uint AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
        private const uint CLSCTX_ALL = 23;
        private const uint COINIT_MULTITHREADED = 0x0;
        private const int VT_BLOB = 0x41;

        private readonly int processId;
        private readonly string outputRaw;
        private readonly string outputMeta;
        private readonly ManualResetEventSlim stopRequested = new ManualResetEventSlim(false);

        private IMMDeviceEnumerator deviceEnumerator;
        private IMMDevice device;
        private IAudioClient audioClient;
        private IAudioCaptureClient captureClient;
        private byte[] inputBuffer = new byte[0];
        private byte[] outputBuffer = new byte[0];
        private long totalFrames;
        private Thread stopThread;

        public ProcessLoopbackCapture(int processId, string outputRaw, string outputMeta)
        {
            this.processId = processId;
            this.outputRaw = outputRaw;
            this.outputMeta = outputMeta;
        }

        public int Run()
        {
            Version osVersion = Environment.OSVersion.Version;
            if (osVersion.Major < 10 || osVersion.Build < 20348)
            {
                Console.Out.WriteLine("ERROR|Windows process loopback capture requires Windows 10 build 20348 or newer.");
                Console.Out.Flush();
                return 2;
            }

            int hr = CoInitializeEx(IntPtr.Zero, COINIT_MULTITHREADED);
            bool uninitialize = hr >= 0;
            IntPtr activationBuffer = IntPtr.Zero;
            IntPtr mixFormatPointer = IntPtr.Zero;

            try
            {
                AUDIOCLIENT_ACTIVATION_PARAMS parameters = new AUDIOCLIENT_ACTIVATION_PARAMS();
                parameters.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE.AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
                parameters.ProcessLoopbackParams.TargetProcessId = (uint)processId;
                parameters.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE.PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;

                activationBuffer = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(AUDIOCLIENT_ACTIVATION_PARAMS)));
                Marshal.StructureToPtr(parameters, activationBuffer, false);

                PROPVARIANT variant = new PROPVARIANT();
                variant.vt = VT_BLOB;
                variant.blob.cbSize = Marshal.SizeOf(typeof(AUDIOCLIENT_ACTIVATION_PARAMS));
                variant.blob.pBlobData = activationBuffer;

                Guid enumeratorClassId = WellKnownGuids.CLSID_MMDeviceEnumerator;
                Guid enumeratorInterfaceId = WellKnownGuids.IID_IMMDeviceEnumerator;
                object enumeratorObject;
                hr = CoCreateInstance(ref enumeratorClassId, IntPtr.Zero, CLSCTX_ALL, ref enumeratorInterfaceId, out enumeratorObject);
                if (hr < 0)
                {
                    Console.Out.WriteLine("ERROR|CoCreateInstance(MMDeviceEnumerator) failed: 0x" + hr.ToString("X8"));
                    Console.Out.Flush();
                    return 3;
                }
                deviceEnumerator = (IMMDeviceEnumerator)enumeratorObject;
                hr = deviceEnumerator.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out device);
                if (hr < 0 || device == null)
                {
                    Console.Out.WriteLine("ERROR|IMMDeviceEnumerator::GetDefaultAudioEndpoint failed: 0x" + hr.ToString("X8"));
                    Console.Out.Flush();
                    return 4;
                }

                Guid audioClientId = WellKnownGuids.IID_IAudioClient;
                object audioClientObject;
                hr = device.Activate(ref audioClientId, CLSCTX_ALL, ref variant, out audioClientObject);
                Marshal.FreeHGlobal(activationBuffer);
                activationBuffer = IntPtr.Zero;
                if (hr < 0 || audioClientObject == null)
                {
                    Console.Out.WriteLine("ERROR|IMMDevice::Activate(IAudioClient) failed: 0x" + hr.ToString("X8"));
                    Console.Out.Flush();
                    return 5;
                }
                audioClient = (IAudioClient)audioClientObject;

                hr = audioClient.GetMixFormat(out mixFormatPointer);
                if (hr < 0 || mixFormatPointer == IntPtr.Zero)
                {
                    Console.Out.WriteLine("ERROR|IAudioClient::GetMixFormat failed: 0x" + hr.ToString("X8"));
                    Console.Out.Flush();
                    return 6;
                }

                WaveFormatInfo inputFormat = WaveFormatInfo.FromPointer(mixFormatPointer);
                hr = audioClient.Initialize(AUDCLNT_SHAREMODE.AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 0, 0, mixFormatPointer, IntPtr.Zero);
                if (hr < 0)
                {
                    Console.Out.WriteLine("ERROR|IAudioClient::Initialize failed: 0x" + hr.ToString("X8"));
                    Console.Out.Flush();
                    return 7;
                }

                object captureService;
                Guid captureClientId = WellKnownGuids.IID_IAudioCaptureClient;
                hr = audioClient.GetService(ref captureClientId, out captureService);
                if (hr < 0 || captureService == null)
                {
                    Console.Out.WriteLine("ERROR|IAudioClient::GetService(IAudioCaptureClient) failed: 0x" + hr.ToString("X8"));
                    Console.Out.Flush();
                    return 8;
                }

                captureClient = (IAudioCaptureClient)captureService;
                hr = audioClient.Start();
                if (hr < 0)
                {
                    Console.Out.WriteLine("ERROR|IAudioClient::Start failed: 0x" + hr.ToString("X8"));
                    Console.Out.Flush();
                    return 9;
                }

                stopThread = new Thread(WaitForStopSignal);
                stopThread.IsBackground = true;
                stopThread.Start();

                Console.Out.WriteLine("READY|" + inputFormat.SampleRate + "|" + inputFormat.Channels + "|s16le");
                Console.Out.Flush();

                Directory.CreateDirectory(Path.GetDirectoryName(outputRaw));
                using (FileStream stream = new FileStream(outputRaw, FileMode.Create, FileAccess.Write, FileShare.Read))
                {
                    CaptureLoop(stream, inputFormat);
                }

                WriteMetadata(inputFormat);
                return 0;
            }
            finally
            {
                if (audioClient != null)
                {
                    try { audioClient.Stop(); } catch { }
                }
                if (mixFormatPointer != IntPtr.Zero)
                {
                    CoTaskMemFree(mixFormatPointer);
                }
                if (activationBuffer != IntPtr.Zero)
                {
                    Marshal.FreeHGlobal(activationBuffer);
                }
                if (uninitialize)
                {
                    CoUninitialize();
                }
            }
        }

        private void CaptureLoop(FileStream stream, WaveFormatInfo format)
        {
            while (!stopRequested.IsSet)
            {
                uint packetSize;
                int hr = captureClient.GetNextPacketSize(out packetSize);
                if (hr < 0)
                {
                    throw new InvalidOperationException("IAudioCaptureClient::GetNextPacketSize failed: 0x" + hr.ToString("X8"));
                }

                if (packetSize == 0)
                {
                    Thread.Sleep(5);
                    continue;
                }

                while (packetSize != 0)
                {
                    IntPtr data;
                    uint frames;
                    AUDCLNT_BUFFERFLAGS flags;
                    ulong devicePosition;
                    ulong qpcPosition;
                    hr = captureClient.GetBuffer(out data, out frames, out flags, out devicePosition, out qpcPosition);
                    if (hr < 0)
                    {
                        throw new InvalidOperationException("IAudioCaptureClient::GetBuffer failed: 0x" + hr.ToString("X8"));
                    }

                    try
                    {
                        WriteSamples(stream, format, data, frames, flags);
                    }
                    finally
                    {
                        captureClient.ReleaseBuffer(frames);
                    }

                    hr = captureClient.GetNextPacketSize(out packetSize);
                    if (hr < 0)
                    {
                        throw new InvalidOperationException("IAudioCaptureClient::GetNextPacketSize failed: 0x" + hr.ToString("X8"));
                    }
                }
            }
        }

        private void WriteSamples(FileStream stream, WaveFormatInfo format, IntPtr data, uint frames, AUDCLNT_BUFFERFLAGS flags)
        {
            int frameCount = (int)frames;
            int outputBytes = frameCount * format.Channels * 2;
            EnsureOutputCapacity(outputBytes);

            if ((flags & AUDCLNT_BUFFERFLAGS.AUDCLNT_BUFFERFLAGS_SILENT) != 0 || data == IntPtr.Zero)
            {
                Array.Clear(outputBuffer, 0, outputBytes);
                stream.Write(outputBuffer, 0, outputBytes);
                totalFrames += frameCount;
                return;
            }

            int inputBytes = frameCount * format.BlockAlign;
            EnsureInputCapacity(inputBytes);
            Marshal.Copy(data, inputBuffer, 0, inputBytes);

            if (format.Kind == WaveSampleKind.IeeeFloat && format.BitsPerSample == 32)
            {
                ConvertFloat32ToS16(frameCount, format.Channels);
            }
            else if (format.Kind == WaveSampleKind.PcmInteger && format.BitsPerSample == 16)
            {
                ConvertS16ToS16(frameCount, format.Channels);
            }
            else if (format.Kind == WaveSampleKind.PcmInteger && format.BitsPerSample == 24)
            {
                ConvertS24ToS16(frameCount, format.Channels);
            }
            else if (format.Kind == WaveSampleKind.PcmInteger && format.BitsPerSample == 32)
            {
                ConvertS32ToS16(frameCount, format.Channels);
            }
            else if (format.Kind == WaveSampleKind.PcmInteger && format.BitsPerSample == 8)
            {
                ConvertU8ToS16(frameCount, format.Channels);
            }
            else
            {
                throw new InvalidOperationException("Unsupported mix format: " + format.Description);
            }

            stream.Write(outputBuffer, 0, outputBytes);
            totalFrames += frameCount;
        }

        private void ConvertFloat32ToS16(int frameCount, int channels)
        {
            int sampleCount = frameCount * channels;
            int inputOffset = 0;
            int outputOffset = 0;
            for (int i = 0; i < sampleCount; i++)
            {
                float sample = BitConverter.ToSingle(inputBuffer, inputOffset);
                if (sample > 1.0f) sample = 1.0f;
                if (sample < -1.0f) sample = -1.0f;
                short value = (short)Math.Round(sample * 32767.0f);
                outputBuffer[outputOffset++] = (byte)(value & 0xFF);
                outputBuffer[outputOffset++] = (byte)((value >> 8) & 0xFF);
                inputOffset += 4;
            }
        }

        private void ConvertS16ToS16(int frameCount, int channels)
        {
            Buffer.BlockCopy(inputBuffer, 0, outputBuffer, 0, frameCount * channels * 2);
        }

        private void ConvertS24ToS16(int frameCount, int channels)
        {
            int sampleCount = frameCount * channels;
            int inputOffset = 0;
            int outputOffset = 0;
            for (int i = 0; i < sampleCount; i++)
            {
                int value = inputBuffer[inputOffset] | (inputBuffer[inputOffset + 1] << 8) | (inputBuffer[inputOffset + 2] << 16);
                if ((value & 0x800000) != 0)
                {
                    value |= unchecked((int)0xFF000000);
                }
                short sample = (short)(value >> 8);
                outputBuffer[outputOffset++] = (byte)(sample & 0xFF);
                outputBuffer[outputOffset++] = (byte)((sample >> 8) & 0xFF);
                inputOffset += 3;
            }
        }

        private void ConvertS32ToS16(int frameCount, int channels)
        {
            int sampleCount = frameCount * channels;
            int inputOffset = 0;
            int outputOffset = 0;
            for (int i = 0; i < sampleCount; i++)
            {
                int value = BitConverter.ToInt32(inputBuffer, inputOffset);
                short sample = (short)(value >> 16);
                outputBuffer[outputOffset++] = (byte)(sample & 0xFF);
                outputBuffer[outputOffset++] = (byte)((sample >> 8) & 0xFF);
                inputOffset += 4;
            }
        }

        private void ConvertU8ToS16(int frameCount, int channels)
        {
            int sampleCount = frameCount * channels;
            int outputOffset = 0;
            for (int i = 0; i < sampleCount; i++)
            {
                int unsignedValue = inputBuffer[i] & 0xFF;
                short sample = (short)((unsignedValue - 128) << 8);
                outputBuffer[outputOffset++] = (byte)(sample & 0xFF);
                outputBuffer[outputOffset++] = (byte)((sample >> 8) & 0xFF);
            }
        }

        private void WriteMetadata(WaveFormatInfo format)
        {
            using (StreamWriter writer = new StreamWriter(outputMeta, false))
            {
                writer.WriteLine("sampleRate=" + format.SampleRate);
                writer.WriteLine("channels=" + format.Channels);
                writer.WriteLine("sampleFormat=s16le");
                writer.WriteLine("totalFrames=" + totalFrames);
            }
        }

        private void WaitForStopSignal()
        {
            try
            {
                string line;
                while ((line = Console.ReadLine()) != null)
                {
                    if (line.Trim().Equals("q", StringComparison.OrdinalIgnoreCase))
                    {
                        stopRequested.Set();
                        return;
                    }
                }
            }
            finally
            {
                stopRequested.Set();
            }
        }

        private void EnsureInputCapacity(int bytes)
        {
            if (inputBuffer.Length < bytes)
            {
                inputBuffer = new byte[bytes];
            }
        }

        private void EnsureOutputCapacity(int bytes)
        {
            if (outputBuffer.Length < bytes)
            {
                outputBuffer = new byte[bytes];
            }
        }

        public void Dispose()
        {
            stopRequested.Set();
            if (stopThread != null && stopThread.IsAlive)
            {
                stopThread.Join(250);
            }
            if (captureClient != null)
            {
                Marshal.ReleaseComObject(captureClient);
                captureClient = null;
            }
            if (audioClient != null)
            {
                Marshal.ReleaseComObject(audioClient);
                audioClient = null;
            }
            if (device != null)
            {
                Marshal.ReleaseComObject(device);
                device = null;
            }
            if (deviceEnumerator != null)
            {
                Marshal.ReleaseComObject(deviceEnumerator);
                deviceEnumerator = null;
            }
            stopRequested.Dispose();
        }

        [DllImport("ole32.dll", ExactSpelling = true)]
        private static extern int CoInitializeEx(IntPtr reserved, uint coInit);

        [DllImport("ole32.dll", ExactSpelling = true)]
        private static extern void CoUninitialize();

        [DllImport("ole32.dll", ExactSpelling = true)]
        private static extern int CoCreateInstance(ref Guid clsid, IntPtr outer, uint clsContext, ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object instance);

        [DllImport("ole32.dll", ExactSpelling = true)]
        private static extern void CoTaskMemFree(IntPtr memory);
    }

    internal enum WaveSampleKind
    {
        PcmInteger,
        IeeeFloat
    }

    internal sealed class WaveFormatInfo
    {
        public readonly ushort Channels;
        public readonly int SampleRate;
        public readonly ushort BlockAlign;
        public readonly ushort BitsPerSample;
        public readonly WaveSampleKind Kind;
        public readonly string Description;

        private WaveFormatInfo(ushort channels, int sampleRate, ushort blockAlign, ushort bitsPerSample, WaveSampleKind kind, string description)
        {
            Channels = channels;
            SampleRate = sampleRate;
            BlockAlign = blockAlign;
            BitsPerSample = bitsPerSample;
            Kind = kind;
            Description = description;
        }

        public static WaveFormatInfo FromPointer(IntPtr formatPointer)
        {
            WAVEFORMATEX format = (WAVEFORMATEX)Marshal.PtrToStructure(formatPointer, typeof(WAVEFORMATEX));
            if (format.wFormatTag == 0x0001)
            {
                return new WaveFormatInfo(format.nChannels, (int)format.nSamplesPerSec, format.nBlockAlign, format.wBitsPerSample, WaveSampleKind.PcmInteger, "PCM");
            }
            if (format.wFormatTag == 0x0003)
            {
                return new WaveFormatInfo(format.nChannels, (int)format.nSamplesPerSec, format.nBlockAlign, format.wBitsPerSample, WaveSampleKind.IeeeFloat, "IEEE_FLOAT");
            }
            if (format.wFormatTag == 0xFFFE)
            {
                WAVEFORMATEXTENSIBLE extensible = (WAVEFORMATEXTENSIBLE)Marshal.PtrToStructure(formatPointer, typeof(WAVEFORMATEXTENSIBLE));
                if (extensible.SubFormat == WellKnownGuids.KSDATAFORMAT_SUBTYPE_PCM)
                {
                    return new WaveFormatInfo(extensible.Format.nChannels, (int)extensible.Format.nSamplesPerSec, extensible.Format.nBlockAlign, extensible.Samples.wValidBitsPerSample == 0 ? extensible.Format.wBitsPerSample : extensible.Samples.wValidBitsPerSample, WaveSampleKind.PcmInteger, "EXTENSIBLE_PCM");
                }
                if (extensible.SubFormat == WellKnownGuids.KSDATAFORMAT_SUBTYPE_IEEE_FLOAT)
                {
                    return new WaveFormatInfo(extensible.Format.nChannels, (int)extensible.Format.nSamplesPerSec, extensible.Format.nBlockAlign, extensible.Samples.wValidBitsPerSample == 0 ? extensible.Format.wBitsPerSample : extensible.Samples.wValidBitsPerSample, WaveSampleKind.IeeeFloat, "EXTENSIBLE_FLOAT");
                }
            }
            throw new InvalidOperationException("Unsupported loopback mix format tag: 0x" + format.wFormatTag.ToString("X4"));
        }
    }

    internal static class WellKnownGuids
    {
        internal static readonly Guid CLSID_MMDeviceEnumerator = new Guid(0xBCDE0395, 0xE52F, 0x467C, 0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E);
        internal static readonly Guid IID_IMMDeviceEnumerator = new Guid(0xA95664D2, 0x9614, 0x4F35, 0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6);
        internal static readonly Guid IID_IAudioClient = new Guid(0x1CB9AD4C, 0xDBFA, 0x4c32, 0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2);
        internal static readonly Guid IID_IAudioCaptureClient = new Guid(0xC8ADBD64, 0xE71E, 0x48A0, 0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17);
        internal static readonly Guid KSDATAFORMAT_SUBTYPE_PCM = new Guid(0x00000001, 0x0000, 0x0010, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71);
        internal static readonly Guid KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = new Guid(0x00000003, 0x0000, 0x0010, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71);
    }

    [ComImport]
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IntPtr devices);

        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice endpoint);
    }

    [ComImport]
    [Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice
    {
        [PreserveSig]
        int Activate(ref Guid iid, uint clsCtx, ref PROPVARIANT activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport]
    [Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        [PreserveSig]
        int Initialize(AUDCLNT_SHAREMODE shareMode, uint streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);

        [PreserveSig]
        int GetBufferSize(out uint bufferSize);

        [PreserveSig]
        int GetStreamLatency(out long latency);

        [PreserveSig]
        int GetCurrentPadding(out uint padding);

        [PreserveSig]
        int IsFormatSupported(AUDCLNT_SHAREMODE shareMode, IntPtr format, out IntPtr closestMatch);

        [PreserveSig]
        int GetMixFormat(out IntPtr deviceFormat);

        [PreserveSig]
        int GetDevicePeriod(out long defaultDevicePeriod, out long minimumDevicePeriod);

        [PreserveSig]
        int Start();

        [PreserveSig]
        int Stop();

        [PreserveSig]
        int Reset();

        [PreserveSig]
        int SetEventHandle(IntPtr eventHandle);

        [PreserveSig]
        int GetService(ref Guid interfaceId, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport]
    [Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioCaptureClient
    {
        [PreserveSig]
        int GetBuffer(out IntPtr data, out uint framesToRead, out AUDCLNT_BUFFERFLAGS flags, out ulong devicePosition, out ulong qpcPosition);

        [PreserveSig]
        int ReleaseBuffer(uint framesRead);

        [PreserveSig]
        int GetNextPacketSize(out uint nextPacketSize);
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct BLOB
    {
        public int cbSize;
        public IntPtr pBlobData;
    }

    [StructLayout(LayoutKind.Explicit)]
    internal struct PROPVARIANT
    {
        [FieldOffset(0)]
        public ushort vt;
        [FieldOffset(8)]
        public BLOB blob;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS
    {
        public uint TargetProcessId;
        public PROCESS_LOOPBACK_MODE ProcessLoopbackMode;
    }

    internal enum AUDIOCLIENT_ACTIVATION_TYPE
    {
        AUDIOCLIENT_ACTIVATION_TYPE_DEFAULT = 0,
        AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK = 1
    }

    internal enum PROCESS_LOOPBACK_MODE
    {
        PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE = 0,
        PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE = 1
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct AUDIOCLIENT_ACTIVATION_PARAMS
    {
        public AUDIOCLIENT_ACTIVATION_TYPE ActivationType;
        public AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS ProcessLoopbackParams;
    }

    internal enum AUDCLNT_SHAREMODE
    {
        AUDCLNT_SHAREMODE_SHARED = 0,
        AUDCLNT_SHAREMODE_EXCLUSIVE = 1
    }

    internal enum EDataFlow
    {
        eRender = 0,
        eCapture = 1,
        eAll = 2
    }

    internal enum ERole
    {
        eConsole = 0,
        eMultimedia = 1,
        eCommunications = 2
    }

    [Flags]
    internal enum AUDCLNT_BUFFERFLAGS : uint
    {
        AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY = 0x1,
        AUDCLNT_BUFFERFLAGS_SILENT = 0x2,
        AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR = 0x4
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    internal struct WAVEFORMATEX
    {
        public ushort wFormatTag;
        public ushort nChannels;
        public uint nSamplesPerSec;
        public uint nAvgBytesPerSec;
        public ushort nBlockAlign;
        public ushort wBitsPerSample;
        public ushort cbSize;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    internal struct WAVEFORMATEXTENSIBLE
    {
        public WAVEFORMATEX Format;
        public SamplesUnion Samples;
        public uint dwChannelMask;
        public Guid SubFormat;
    }

    [StructLayout(LayoutKind.Explicit, Pack = 2)]
    internal struct SamplesUnion
    {
        [FieldOffset(0)]
        public ushort wValidBitsPerSample;
        [FieldOffset(0)]
        public ushort wSamplesPerBlock;
        [FieldOffset(0)]
        public ushort wReserved;
    }
}
"@

Add-Type -TypeDefinition $source -Language CSharp
exit [PerfectFlow.WindowsAudio.ProcessLoopbackHost]::Run($ProcessId, $OutputRaw, $OutputMeta)
