# Keep Media3 TextRenderer and related classes for subtitle support
# experimentalSetLegacyDecodingEnabled is marked @ExperimentalApi but we need it for TTML/VTT/SRT captions
-keep class androidx.media3.exoplayer.text.TextRenderer {
    public void experimentalSetLegacyDecodingEnabled(boolean);
    *** *(...);
}
-keep class androidx.media3.exoplayer.text.** { *; }
-keep class androidx.media3.extractor.text.** { *; }
-keep class androidx.media3.common.text.** { *; }

# Keep subtitle-related MediaItem configurations
-keep class androidx.media3.common.MediaItem$SubtitleConfiguration { *; }
-keep class androidx.media3.common.MediaItem$SubtitleConfiguration$Builder { *; }

# Keep SingleSampleMediaSource for subtitle loading
-keep class androidx.media3.exoplayer.source.SingleSampleMediaSource { *; }
-keep class androidx.media3.exoplayer.source.SingleSampleMediaSource$Factory { *; }

# NewPipe extractor - keep subtitle related classes
-keep class org.schabi.newpipe.extractor.stream.SubtitlesStream { *; }
-keep class org.schabi.newpipe.extractor.MediaFormat { *; }
