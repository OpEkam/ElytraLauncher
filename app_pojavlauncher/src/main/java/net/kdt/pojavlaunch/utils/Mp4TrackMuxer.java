package net.kdt.pojavlaunch.utils;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Copies the compressed video and audio tracks from two MP4 files into one
 * MP4 without decoding or re-encoding either stream.
 */
public final class Mp4TrackMuxer {
    private static final int DEFAULT_SAMPLE_BUFFER_SIZE = 2 * 1024 * 1024;

    private Mp4TrackMuxer() {}

    public static void combine(File videoFile, File audioFile, File outputFile)
            throws IOException {
        if (videoFile == null || audioFile == null || outputFile == null
                || !videoFile.isFile() || !audioFile.isFile()) {
            throw new IOException("Video, audio, and output files are required");
        }
        File parent = outputFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()
                && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create output directory: " + parent);
        }

        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            audio.setDataSource(audioFile.getAbsolutePath());

            int videoInputTrack = findTrack(video, "video/");
            int audioInputTrack = findTrack(audio, "audio/");
            if (videoInputTrack < 0) throw new IOException("Video MP4 has no video track");
            if (audioInputTrack < 0) throw new IOException("Audio MP4 has no audio track");

            video.selectTrack(videoInputTrack);
            audio.selectTrack(audioInputTrack);
            MediaFormat videoFormat = video.getTrackFormat(videoInputTrack);
            MediaFormat audioFormat = audio.getTrackFormat(audioInputTrack);

            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOutputTrack = muxer.addTrack(videoFormat);
            int audioOutputTrack = muxer.addTrack(audioFormat);
            muxer.start();
            muxerStarted = true;

            int videoCapacity = sampleCapacity(videoFormat);
            int audioCapacity = sampleCapacity(audioFormat);
            ByteBuffer videoBuffer = ByteBuffer.allocateDirect(videoCapacity);
            ByteBuffer audioBuffer = ByteBuffer.allocateDirect(audioCapacity);
            MediaCodecSample videoSample = new MediaCodecSample();
            MediaCodecSample audioSample = new MediaCodecSample();

            boolean hasVideo = readSample(video, videoBuffer, videoSample);
            boolean hasAudio = readSample(audio, audioBuffer, audioSample);
            while (hasVideo || hasAudio) {
                boolean writeVideo = hasVideo && (!hasAudio
                        || videoSample.info.presentationTimeUs
                        <= audioSample.info.presentationTimeUs);
                if (writeVideo) {
                    muxer.writeSampleData(videoOutputTrack, videoBuffer, videoSample.info);
                    hasVideo = readSample(video, videoBuffer, videoSample);
                } else {
                    muxer.writeSampleData(audioOutputTrack, audioBuffer, audioSample.info);
                    hasAudio = readSample(audio, audioBuffer, audioSample);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Could not combine MP4 audio and video tracks", e);
        } finally {
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (RuntimeException ignored) {}
                }
                try { muxer.release(); } catch (RuntimeException ignored) {}
            }
            try { video.release(); } catch (RuntimeException ignored) {}
            try { audio.release(); } catch (RuntimeException ignored) {}
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int sampleCapacity(MediaFormat format) {
        int capacity = DEFAULT_SAMPLE_BUFFER_SIZE;
        try {
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                capacity = Math.max(capacity,
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
        } catch (RuntimeException ignored) {}
        return Math.min(capacity, 16 * 1024 * 1024);
    }

    private static boolean readSample(MediaExtractor extractor, ByteBuffer buffer,
                                      MediaCodecSample sample) throws IOException {
        buffer.clear();
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0) return false;
        if (size > buffer.capacity()) {
            throw new IOException("Encoded MP4 sample exceeds the copy buffer");
        }
        sample.info.offset = 0;
        sample.info.size = size;
        sample.info.presentationTimeUs = extractor.getSampleTime();
        sample.info.flags = extractor.getSampleFlags();
        buffer.position(0);
        buffer.limit(size);
        extractor.advance();
        return true;
    }

    private static final class MediaCodecSample {
        final android.media.MediaCodec.BufferInfo info =
                new android.media.MediaCodec.BufferInfo();
    }
}
