package net.rivergod.webp.imageio;

import com.google.protobuf.ByteString;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import net.rivergod.webp.imageio.proto.v1.DecodeOptions;
import net.rivergod.webp.imageio.proto.v1.FrameHeader;
import net.rivergod.webp.imageio.proto.v1.GetFeaturesResponse;
import net.rivergod.webp.imageio.proto.v1.ImageFeatures;
import net.rivergod.webp.imageio.proto.v1.PixelFormat;

/**
 * WebP {@link ImageReader}. 입력 전체를 읽어 서버에 보내고, 크기·프레임·메타데이터는 GetFeatures, 픽셀은 Decode
 * (애니메이션 순차 읽기는 DecodeAnimation)로 받는다(docs/design/grpc-api.md, ADR-0014). ImageIO 규약대로 스레드
 * 안전하지 않다.
 */
final class WebPImageReader extends ImageReader {

    private WebPClient client;
    private long streamStart;
    private byte[] data;
    private GetFeaturesResponse features;
    private AnimationStream cursor;

    WebPImageReader(ImageReaderSpi spi) {
        super(spi);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        clearCache();
        streamStart = 0;
        if (input instanceof ImageInputStream in) {
            try {
                streamStart = in.getStreamPosition();
            } catch (IOException e) {
                streamStart = 0;
            }
        }
    }

    private void clearCache() {
        closeCursor();
        data = null;
        features = null;
    }

    /** ADR-0014: 열어 둔 스트림은 서버 슬롯을 차지하므로 입력이 바뀌거나 리더를 버릴 때 바로 취소한다. */
    private void closeCursor() {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
    }

    private WebPClient client() throws IIOException {
        if (client == null) {
            client = WebPClient.fromEnvironment();
        }
        return client;
    }

    /** 입력 전체(서버 max_input_bytes 이하)를 한 번 읽어 기억한다. */
    private byte[] data() throws IOException {
        if (data != null) {
            return data;
        }
        if (!(getInput() instanceof ImageInputStream in)) {
            throw new IllegalStateException("No input source set");
        }
        long max = client().limits().getMaxInputBytes();
        if (in.getStreamPosition() != streamStart) {
            in.seek(streamStart);
        }
        // 길이를 알면 먼저 상한을 검사하고 정확한 크기로 읽는다. 모르면 상한까지만 읽는다.
        long length = in.length();
        if (length >= 0) {
            long size = length - streamStart;
            if (size > max) {
                throw new IIOException("WebP input of " + size + " bytes exceeds server limit " + max + " bytes");
            }
            byte[] bytes = new byte[(int) size];
            in.readFully(bytes);
            data = bytes;
        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int r; (r = in.read(buf)) > 0; ) {
                total += r;
                if (total > max) {
                    throw new IIOException("WebP input exceeds server limit " + max + " bytes");
                }
                out.write(buf, 0, r);
            }
            data = out.toByteArray();
        }
        if (data.length == 0) {
            throw new IIOException("Empty WebP input");
        }
        return data;
    }

    private GetFeaturesResponse response() throws IOException {
        if (features == null) {
            features = client().getFeatures(data(), !ignoreMetadata);
        }
        return features;
    }

    private ImageFeatures features() throws IOException {
        return response().getFeatures();
    }

    private void checkIndex(int imageIndex) throws IOException {
        int count = features().getFrameCount();
        if (imageIndex < 0 || imageIndex >= count) {
            throw new IndexOutOfBoundsException("imageIndex " + imageIndex + " out of range [0, " + count + ")");
        }
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return features().getFrameCount();
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return features().getWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return features().getHeight();
    }

    /** alpha가 있거나 애니메이션(합성 캔버스는 투명할 수 있다)이면 TYPE_INT_ARGB, 아니면 TYPE_INT_RGB(ADR-0013). */
    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ImageFeatures f = features();
        int type = f.getHasAlpha() || f.getHasAnimation() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        return List.of(ImageTypeSpecifier.createFromBufferedImageType(type)).iterator();
    }

    /** ICC/EXIF/XMP와 애니메이션 전체 정보(ADR-0014). {@code ignoreMetadata}면 null. */
    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        if (ignoreMetadata) {
            return null;
        }
        GetFeaturesResponse r = response();
        return WebPStreamMetadata.from(r.hasMetadata() ? r.getMetadata() : null,
                r.getFeatures().hasAnimation() ? r.getFeatures().getAnimation() : null);
    }

    /** 프레임 정보(native)와 표준 포맷 노드(ADR-0014). {@code ignoreMetadata}면 null. */
    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        if (ignoreMetadata) {
            return null;
        }
        ImageFeatures f = features();
        if (f.getHasAnimation() && f.getAnimation().getFramesCount() > imageIndex) {
            return WebPImageMetadata.forFrame(f.getAnimation().getFrames(imageIndex));
        }
        return WebPImageMetadata.forStill(f);
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        if (param != null && (param.getSourceBands() != null || param.getDestinationBands() != null)) {
            throw new IllegalArgumentException("source/destination bands are not supported");
        }
        ImageFeatures f = features();
        int width = f.getWidth();
        int height = f.getHeight();
        checkPixelLimits(width, height);

        // 대상 이미지와 영역을 정한다. 기본 경우(영역·서브샘플링 없음, 표준 int 타입)면 대상 버퍼에 직접 쓴다.
        BufferedImage dest = getDestination(param, getImageTypes(imageIndex), width, height);
        Rectangle src = new Rectangle();
        Rectangle dst = new Rectangle();
        computeRegions(param, width, height, dest, src, dst);
        boolean direct = isDirect(param, dest, width, height, src, dst);
        int[] target = direct ? ((DataBufferInt) dest.getRaster().getDataBuffer()).getData() : null;
        boolean alpha = !direct || dest.getColorModel().hasAlpha();

        clearAbortRequest();
        processImageStarted(imageIndex);
        long maxPixels = client().config().maxPixels();
        ArgbFrameSink sink = new ArgbFrameSink(width, height, maxPixels, alpha, target);
        ProgressReceiver receiver = new ProgressReceiver(sink, imageIndex, (long) width * height * 4);
        try {
            if (f.getHasAnimation()) {
                readAnimationFrame(imageIndex, receiver, maxPixels);
            } else {
                client().decode(data(), decodeOptions(imageIndex, maxPixels), receiver);
            }
        } catch (IIOException e) {
            if (abortRequested()) {
                closeCursor();
                processReadAborted();
                return dest;
            }
            throw e;
        }

        if (!direct) {
            copyRegion(sink.argb(), width, src, dst, param, dest);
        }
        processImageComplete();
        return dest;
    }

    private static DecodeOptions decodeOptions(int frameIndex, long maxPixels) {
        DecodeOptions.Builder options = DecodeOptions.newBuilder()
                .setFrameIndex(frameIndex)
                .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8);
        if (maxPixels > 0) {
            options.setMaxPixels(maxPixels);
        }
        return options.build();
    }

    /**
     * ADR-0014 순차 읽기: 열어 둔 스트림의 다음 프레임이면 거기서 받고, 0번이면 스트림을 연다. 그 밖에는 스트림을 닫고
     * Decode(frame_index)로 한 프레임만 받는다. 스트림이 실패하면(데드라인 등) 닫고 Decode로 한 번 다시 받는다.
     */
    private void readAnimationFrame(int imageIndex, ProgressReceiver receiver, long maxPixels) throws IOException {
        if (cursor != null && cursor.nextFrame() == imageIndex) {
            try {
                cursor.readFrame(receiver);
                return;
            } catch (IIOException e) {
                closeCursor();
                if (abortRequested() || receiver.started()) {
                    throw e;
                }
            }
        } else {
            closeCursor();
            if (imageIndex == 0) {
                cursor = client().openDecodeAnimation(data(), maxPixels);
                cursor.readFrame(receiver);
                return;
            }
        }
        client().decode(data(), decodeOptions(imageIndex, maxPixels), receiver);
    }

    /**
     * 대상 버퍼를 할당하기 전에 크기를 검사한다(클라이언트 쪽 decompression bomb 방어, docs/design/grpc-api.md §3).
     * 서버도 같은 검사를 하지만, 대상 이미지는 Decode를 부르기 전에 만들어지므로 여기서 먼저 막아야 한다.
     */
    private void checkPixelLimits(int width, int height) throws IIOException {
        long pixels = (long) width * height;
        long serverMax = client().limits().getMaxPixels();
        if (serverMax > 0 && pixels > serverMax) {
            throw new IIOException("WebP image exceeds server limit: " + width + "x" + height + " = " + pixels
                    + " pixels > max_pixels " + serverMax);
        }
        long clientMax = client().config().maxPixels();
        if (clientMax > 0 && pixels > clientMax) {
            throw new IIOException("WebP image " + width + "x" + height + " exceeds client limit " + clientMax
                    + " pixels (webp.imageio.max-pixels)");
        }
        if (pixels > ArgbFrameSink.MAX_ARRAY_PIXELS) {
            throw new IIOException("WebP image " + width + "x" + height + " does not fit in a Java array");
        }
    }

    private static boolean isDirect(
            ImageReadParam param, BufferedImage dest, int width, int height, Rectangle src, Rectangle dst) {
        boolean defaultParam = param == null
                || (param.getSourceXSubsampling() == 1 && param.getSourceYSubsampling() == 1);
        boolean fullRegions = src.x == 0 && src.y == 0 && src.width == width && src.height == height
                && dst.x == 0 && dst.y == 0 && dst.width == width && dst.height == height;
        boolean standardType = (dest.getType() == BufferedImage.TYPE_INT_ARGB || dest.getType() == BufferedImage.TYPE_INT_RGB)
                && dest.getWidth() == width && dest.getHeight() == height
                && dest.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sm
                && sm.getScanlineStride() == width
                && dest.getRaster().getDataBuffer().getOffset() == 0
                && dest.getRaster().getSampleModelTranslateX() == 0
                && dest.getRaster().getSampleModelTranslateY() == 0;
        return defaultParam && fullRegions && standardType;
    }

    /** 전체 ARGB에서 소스 영역을 서브샘플링해 대상 영역으로 옮긴다(setRGB가 대상 타입으로 변환한다). */
    private static void copyRegion(int[] argb, int width, Rectangle src, Rectangle dst, ImageReadParam param, BufferedImage dest) {
        int xs = param == null ? 1 : param.getSourceXSubsampling();
        int ys = param == null ? 1 : param.getSourceYSubsampling();
        int[] row = new int[dst.width];
        for (int j = 0; j < dst.height; j++) {
            int base = (src.y + j * ys) * width + src.x;
            for (int k = 0; k < dst.width; k++) {
                row[k] = argb[base + k * xs];
            }
            dest.setRGB(dst.x, dst.y + j, dst.width, 1, row, 0, dst.width);
        }
    }

    /** 진행률을 알리고 abort 요청과 프레임 번호를 확인하는 수신기 래퍼. */
    private final class ProgressReceiver implements WebPClient.FrameReceiver {
        private final ArgbFrameSink sink;
        private final int frameIndex;
        private final long totalBytes;
        private long received;
        private int lastPercent = -1;
        private boolean started;

        ProgressReceiver(ArgbFrameSink sink, int frameIndex, long totalBytes) {
            this.sink = sink;
            this.frameIndex = frameIndex;
            this.totalBytes = Math.max(1, totalBytes);
        }

        /** 헤더를 이미 받았으면 true(그 뒤의 실패는 다른 경로로 다시 받을 수 없다). */
        boolean started() {
            return started;
        }

        @Override
        public void header(FrameHeader header) throws IIOException {
            if (header.getFrameIndex() != frameIndex) {
                throw new IIOException("WebP server sent frame " + header.getFrameIndex() + ", expected " + frameIndex);
            }
            started = true;
            sink.header(header);
        }

        @Override
        public void pixels(ByteString chunk) throws IIOException {
            if (abortRequested()) {
                throw new IIOException("read aborted");
            }
            sink.pixels(chunk);
            received += chunk.size();
            int percent = (int) (received * 100 / totalBytes);
            if (percent != lastPercent) {
                lastPercent = percent;
                processImageProgress(percent);
            }
        }

        @Override
        public void finish() throws IIOException {
            sink.finish();
        }
    }

    @Override
    public void reset() {
        super.reset();
        clearCache();
    }

    @Override
    public void dispose() {
        clearCache();
    }
}
