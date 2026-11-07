# kotlinx.serialization, Room, OkHttp and Coil ship their own consumer rules.
# Add app-specific keeps here only when a release build proves one is needed.

# PdfBox-Android's optional JP2Android decoder is not included: book import extracts text,
# never renders JPEG2000 images. Upstream explicitly supports omitting this decoder.
-dontwarn com.gemalto.jp2.JP2Decoder

# WorkManager reconstructs this persisted Worker by its class name after process restart.
-keep class com.cleo.cleos.data.WorldOutsideWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class com.cleo.cleos.data.WorldTopicWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
