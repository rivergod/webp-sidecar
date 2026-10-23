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
import net.rivergod.webp.imageio.proto.v1.EncodeOptions;
import net.rivergod.webp.imageio.proto.v1.EncodedHeader;
import net.rivergod.webp.imageio.proto.v1.PixelFormat;

/**
 * WebP {@link ImageWriter}. 이미지를 RGBA8 조각으로 바꿔 Encode로 보내고 결과 WebP 파일을 출력 스트림에 쓴다.
 * ImageIO 규약대로 스레드 안전하지 않다.
 */
final class WebPImageWriter extends ImageWriter {

    private WebPClient client;

    WebPImageWriter(ImageWriterSpi spi) {
        super(spi);
    }

    private WebPClient client() throws IIOException {
        if (client == null) {
            client = WebPClient.fromEnvironment();
        }
        return client;
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new WebPWriteParam(getLocale());
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        if (!(getOutput() instanceof ImageOutputStream out)) {
            throw new IllegalStateException("No output set");
        }
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("Raster output is not supported");
        }

        // 소스 영역과 서브샘플링을 적용한 결과 크기를 정한다.
        BufferedImage source = asBufferedImage(image.getRenderedImage());
        Rectangle region = new Rectangle(0, 0, source.getWidth(), source.getHeight());
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
        int width = (region.width + xs - 1) / xs;
        int height = (region.height + ys - 1) / ys;

        clearAbortRequest();
        processImageStarted(0);
        EncodeOptions options = EncodeOptions.newBuilder()
                .setWidth(width)
                .setHeight(height)
                .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8)
                .setParams(WebPWriteParam.toEncodeParams(param))
                .build();
        int chunk = Math.min(WebPClient.DEFAULT_CHUNK_BYTES, client().limits().getMaxChunkBytes());
        Output output = new Output(out);
        try {
            client().encode(options, new RgbaChunks(source, region, xs, ys, width, height, chunk), output);
        } catch (IIOException e) {
            if (abortRequested()) {
                processWriteAborted();
                return;
            }
            throw e;
        }
        if (output.declared != output.written) {
            throw new IIOException("WebP server declared " + output.declared + " bytes but sent " + output.written);
        }
        processImageComplete();
    }

    /** 결과 WebP 조각을 출력 스트림에 쓴다(gRPC 스레드에서 순서대로 불린다). */
    private static final class Output implements WebPClient.EncodedReceiver {
        private final ImageOutputStream out;
        private final byte[] buf = new byte[64 * 1024];
        long declared = -1;
        long written;

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
    }

    /**
     * 위쪽 행부터 RGBA8 조각을 만든다. 서버가 받을 준비가 될 때 하나씩 만들므로 이미지 전체를 한 번에 복사하지 않는다.
     * 조각마다 새 배열을 쓴다(감싼 배열을 gRPC가 나중에 직렬화하므로 재사용하면 안 된다).
     */
    private final class RgbaChunks implements Iterator<ByteString> {
        private final BufferedImage source;
        private final Rectangle region;
        private final int xs;
        private final int ys;
        private final int width;
        private final int height;
        private final int rowsPerChunk;
        private final int[] row;
        private int nextRow;

        RgbaChunks(BufferedImage source, Rectangle region, int xs, int ys, int width, int height, int chunkBytes) {
            this.source = source;
            this.region = region;
            this.xs = xs;
            this.ys = ys;
            this.width = width;
            this.height = height;
            this.rowsPerChunk = Math.max(1, chunkBytes / (width * 4));
            this.row = new int[region.width];
        }

        @Override
        public boolean hasNext() {
            return nextRow < height;
        }

        @Override
        public ByteString next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (abortRequested()) {
                throw new IllegalStateException("write aborted");
            }
            int rows = Math.min(rowsPerChunk, height - nextRow);
            byte[] bytes = new byte[rows * width * 4];
            int o = 0;
            for (int r = 0; r < rows; r++) {
                int sy = region.y + (nextRow + r) * ys;
                // getRGB는 sRGB 비-premultiplied ARGB로 바꿔 준다(ADR-0004의 와이어 포맷).
                source.getRGB(region.x, sy, region.width, 1, row, 0, region.width);
                for (int k = 0; k < width; k++) {
                    int argb = row[k * xs];
                    bytes[o++] = (byte) (argb >> 16);
                    bytes[o++] = (byte) (argb >> 8);
                    bytes[o++] = (byte) argb;
                    bytes[o++] = (byte) (argb >>> 24);
                }
            }
            nextRow += rows;
            processImageProgress(nextRow * 100f / height);
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
