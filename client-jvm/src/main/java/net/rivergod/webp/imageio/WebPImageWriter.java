package net.rivergod.webp.imageio;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import net.rivergod.webp.imageio.proto.v1.EncodeAnimationOptions;
import net.rivergod.webp.imageio.proto.v1.EncodeOptions;
import net.rivergod.webp.imageio.proto.v1.EncodedHeader;
import net.rivergod.webp.imageio.proto.v1.PixelFormat;

/**
 * WebP {@link ImageWriter}. 이미지를 RGBA8 조각으로 바꿔 Encode(정지 이미지) 또는 EncodeAnimation(시퀀스 쓰기)으로
 * 보내고, 결과 WebP 파일을 출력 스트림에 쓴다. ICC/EXIF/XMP는 스트림 메타데이터에서 넣는다(ADR-0014).
 * ImageIO 규약대로 스레드 안전하지 않다.
 */
final class WebPImageWriter extends ImageWriter {

    private WebPClient client;

    // 시퀀스 쓰기 상태(prepareWriteSequence → writeToSequence × N → endWriteSequence).
    private WebPStreamMetadata sequenceMetadata;
    private WebPClient.AnimationUpload upload;
    private Output sequenceOutput;
    private int canvasWidth;
    private int canvasHeight;

    WebPImageWriter(ImageWriterSpi spi) {
        super(spi);
    }

    private WebPClient client() throws IIOException {
        if (client == null) {
            client = WebPClient.fromEnvironment();
        }
        return client;
    }

    private ImageOutputStream output() {
        if (!(getOutput() instanceof ImageOutputStream out)) {
            throw new IllegalStateException("No output set");
        }
        return out;
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new WebPWriteParam(getLocale());
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return new WebPStreamMetadata();
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return new WebPImageMetadata();
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        try {
            return inData == null ? null : WebPStreamMetadata.of(inData);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
        if (inData == null) {
            return null;
        }
        try {
            WebPImageMetadata m = new WebPImageMetadata();
            m.durationMs = WebPImageMetadata.durationOf(inData);
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        ImageOutputStream out = output();
        Source source = Source.of(image, param);
        WebPStreamMetadata metadata = WebPStreamMetadata.of(streamMetadata);

        clearAbortRequest();
        processImageStarted(0);
        EncodeOptions options = EncodeOptions.newBuilder()
                .setWidth(source.width)
                .setHeight(source.height)
                .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8)
                .setParams(WebPWriteParam.toEncodeParams(param))
                .setMetadata(metadata.toProto())
                .build();
        Output output = new Output(out);
        try {
            client().encode(options, rgbaChunks(source), output);
        } catch (IIOException e) {
            if (abortRequested()) {
                processWriteAborted();
                return;
            }
            throw e;
        }
        output.check();
        processImageComplete();
    }

    @Override
    public boolean canWriteSequence() {
        return true;
    }

    /**
     * 애니메이션 쓰기를 시작한다. 반복 횟수·배경색과 ICC/EXIF/XMP는 스트림 메타데이터에서 읽는다(없으면 0, libwebp 기본값).
     */
    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {
        output();
        if (sequenceMetadata != null) {
            throw new IllegalStateException("A sequence is already being written");
        }
        sequenceMetadata = WebPStreamMetadata.of(streamMetadata);
    }

    /**
     * 프레임 하나를 쓴다. 모든 프레임은 첫 프레임과 같은 크기여야 한다(ADR-0014). 표시 시간은 이미지 메타데이터의
     * {@code Frame@durationMs}(없으면 100 ms)를 쓴다. 압축 설정은 첫 프레임의 param을 쓴다.
     */
    @Override
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        if (sequenceMetadata == null) {
            throw new IllegalStateException("prepareWriteSequence was not called");
        }
        Source source = Source.of(image, param);
        int duration = WebPImageMetadata.durationOf(image.getMetadata());
        if (upload == null) {
            // 첫 프레임: 캔버스 크기를 정하고 EncodeAnimation을 연다.
            canvasWidth = source.width;
            canvasHeight = source.height;
            EncodeAnimationOptions options = EncodeAnimationOptions.newBuilder()
                    .setCanvasWidth(canvasWidth)
                    .setCanvasHeight(canvasHeight)
                    .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8)
                    .setLoopCount(sequenceMetadata.loopCount == null ? 0 : sequenceMetadata.loopCount)
                    .setBackgroundColorArgb(sequenceMetadata.backgroundArgb == null
                            ? WebPStreamMetadata.DEFAULT_BACKGROUND_ARGB : sequenceMetadata.backgroundArgb)
                    .setParams(WebPWriteParam.toEncodeParams(param))
                    .setMetadata(sequenceMetadata.toProto())
                    .build();
            sequenceOutput = new Output(output());
            upload = client().startEncodeAnimation(options, sequenceOutput);
        } else if (source.width != canvasWidth || source.height != canvasHeight) {
            throw new IIOException("All frames must be " + canvasWidth + "x" + canvasHeight + ", got "
                    + source.width + "x" + source.height);
        }
        clearAbortRequest();
        upload.frame(duration, rgbaChunks(source));
    }

    /** 애니메이션 쓰기를 끝내고 결과 파일을 출력에 쓴다. */
    @Override
    public void endWriteSequence() throws IOException {
        if (sequenceMetadata == null) {
            throw new IllegalStateException("prepareWriteSequence was not called");
        }
        WebPClient.AnimationUpload current = upload;
        Output out = sequenceOutput;
        sequenceMetadata = null;
        upload = null;
        sequenceOutput = null;
        if (current == null) {
            throw new IIOException("No frames were written to the sequence");
        }
        current.finish();
        out.check();
    }

    @Override
    public void reset() {
        super.reset();
        cancelSequence();
    }

    @Override
    public void dispose() {
        cancelSequence();
    }

    private void cancelSequence() {
        if (upload != null) {
            upload.cancel();
        }
        upload = null;
        sequenceMetadata = null;
        sequenceOutput = null;
    }

    /** 결과 WebP 조각을 출력 스트림에 쓴다(gRPC 스레드에서 순서대로 불린다). */
    private static final class Output implements WebPClient.EncodedReceiver {
        private final ImageOutputStream out;
        private final byte[] buf = new byte[64 * 1024];
        private long declared = -1;
        private long written;

        Output(ImageOutputStream out) {
            this.out = out;
        }

        @Override
        public void header(EncodedHeader header) {
            declared = header.getWebpBytes();
        }

        @Override
        public void data(ByteString chunk) throws IOException {
            ByteBuffer bytes = chunk.asReadOnlyByteBuffer();
            while (bytes.hasRemaining()) {
                int len = Math.min(buf.length, bytes.remaining());
                bytes.get(buf, 0, len);
                out.write(buf, 0, len);
            }
            written += chunk.size();
        }

        void check() throws IIOException {
            if (declared != written) {
                throw new IIOException("WebP server declared " + declared + " bytes but sent " + written);
            }
        }
    }

    /** 소스 영역과 서브샘플링을 적용한 입력 이미지. */
    private record Source(BufferedImage image, Rectangle region, int xs, int ys, int width, int height) {

        static Source of(IIOImage iio, ImageWriteParam param) throws IIOException {
            if (iio.hasRaster()) {
                throw new UnsupportedOperationException("Raster output is not supported");
            }
            BufferedImage image = asBufferedImage(iio.getRenderedImage());
            Rectangle region = new Rectangle(0, 0, image.getWidth(), image.getHeight());
            int xs = 1;
            int ys = 1;
            if (param != null) {
                if (param.getSourceRegion() != null) {
                    region = region.intersection(param.getSourceRegion());
                }
                xs = param.getSourceXSubsampling();
                ys = param.getSourceYSubsampling();
                region.x += param.getSubsamplingXOffset();
                region.y += param.getSubsamplingYOffset();
                region.width -= param.getSubsamplingXOffset();
                region.height -= param.getSubsamplingYOffset();
            }
            if (region.isEmpty()) {
                throw new IIOException("Empty source region");
            }
            return new Source(image, region, xs, ys, (region.width + xs - 1) / xs, (region.height + ys - 1) / ys);
        }
    }

    private Iterator<ByteString> rgbaChunks(Source source) throws IIOException {
        int chunk = Math.min(WebPClient.DEFAULT_CHUNK_BYTES, client().limits().getMaxChunkBytes());
        return new RgbaChunks(source, chunk);
    }

    /**
     * 위쪽 행부터 RGBA8 조각을 만든다. 서버가 받을 준비가 될 때 하나씩 만들므로 이미지 전체를 한 번에 복사하지 않는다.
     * 조각마다 새 배열을 쓴다(감싼 배열을 gRPC가 나중에 직렬화하므로 재사용하면 안 된다).
     */
    private final class RgbaChunks implements Iterator<ByteString> {
        private final Source s;
        private final int rowsPerChunk;
        private final int[] row;
        private int nextRow;

        RgbaChunks(Source source, int chunkBytes) {
            this.s = source;
            this.rowsPerChunk = Math.max(1, chunkBytes / (source.width * 4));
            this.row = new int[source.region.width];
        }

        @Override
        public boolean hasNext() {
            return nextRow < s.height;
        }

        @Override
        public ByteString next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (abortRequested()) {
                throw new IllegalStateException("write aborted");
            }
            int rows = Math.min(rowsPerChunk, s.height - nextRow);
            byte[] bytes = new byte[rows * s.width * 4];
            int o = 0;
            for (int r = 0; r < rows; r++) {
                int sy = s.region.y + (nextRow + r) * s.ys;
                // getRGB는 sRGB 비-premultiplied ARGB로 바꿔 준다(ADR-0004의 와이어 포맷).
                s.image.getRGB(s.region.x, sy, s.region.width, 1, row, 0, s.region.width);
                for (int k = 0; k < s.width; k++) {
                    int argb = row[k * s.xs];
                    bytes[o++] = (byte) (argb >> 16);
                    bytes[o++] = (byte) (argb >> 8);
                    bytes[o++] = (byte) argb;
                    bytes[o++] = (byte) (argb >>> 24);
                }
            }
            nextRow += rows;
            processImageProgress(nextRow * 100f / s.height);
            return UnsafeByteOperations.unsafeWrap(bytes);
        }
    }

    private static BufferedImage asBufferedImage(RenderedImage image) {
        if (image instanceof BufferedImage b) {
            return b;
        }
        ColorModel cm = image.getColorModel();
        WritableRaster raster = cm.createCompatibleWritableRaster(image.getWidth(), image.getHeight());
        image.copyData(raster.createWritableTranslatedChild(image.getMinX(), image.getMinY()));
        return new BufferedImage(cm, raster, cm.isAlphaPremultiplied(), null);
    }
}
