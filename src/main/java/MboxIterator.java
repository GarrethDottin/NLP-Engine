import com.google.common.base.Charsets;
import java.io.*;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class that provides an iterator over email messages inside an mbox file.
 *
 * @author Ioan Eugen Stan <stan.ieugen@gmail.com>
 */
public class MboxIterator implements Iterable<CharBuffer>, Closeable {

    private final FileInputStream theFile;
    private final CharBuffer mboxCharBuffer;
    private Matcher fromLineMathcer;
    private boolean fromLineFound;
    private final MappedByteBuffer byteBuffer;
    private final CharsetDecoder DECODER;
    /** Change to true in the final invocation so bytes at teh end of the input are
     * decoding is done properly and if incmplete will cause a return of mall-formed input.
     */
    private boolean endOfInputFlag = false;
    private final int maxMessageSize;
    private final Pattern MESSAGE_START;
    private int findStart = -1;
    private int findEnd = -1;

    private MboxIterator(final File mbox,
                         final Charset charset,
                         final String regexpPattern,
                         final int regexpFlags,
                         final int MAX_MESSAGE_SIZE)
            throws FileNotFoundException, IOException, CharConversionException {
        //TODO: do better exception handling - try to process some of them maybe?
        this.maxMessageSize = MAX_MESSAGE_SIZE;
        this.MESSAGE_START = Pattern.compile(regexpPattern, regexpFlags);
        this.DECODER = charset.newDecoder();
        this.mboxCharBuffer = CharBuffer.allocate(MAX_MESSAGE_SIZE);
        this.theFile = new FileInputStream(mbox);
        this.byteBuffer = theFile.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, theFile.getChannel().size());
        initMboxIterator();
    }

    private void initMboxIterator() throws IOException, CharConversionException {
        decodeNextCharBuffer();
        fromLineMathcer = MESSAGE_START.matcher(mboxCharBuffer);
        fromLineFound = fromLineMathcer.find();
        if (fromLineFound) {
            saveFindPositions(fromLineMathcer);
        } else if (fromLineMathcer.hitEnd()) {
            throw new IllegalArgumentException("File does not contain From_ lines! Maybe not be a vaild Mbox.");
        }
    }

    private void decodeNextCharBuffer() throws CharConversionException {
        CoderResult coderResult = DECODER.decode(byteBuffer, mboxCharBuffer, endOfInputFlag);
        updateEndOfInputFlag();
        mboxCharBuffer.flip();
        if (coderResult.isError()) {
            if (coderResult.isMalformed()) {
                throw new CharConversionException("Malformed input!");
            } else if (coderResult.isUnmappable()) {
                throw new CharConversionException("Unmappable character!");
            }
        }
    }

    private void updateEndOfInputFlag() {
        if (byteBuffer.remaining() <= maxMessageSize) {
            endOfInputFlag = true;
        }
    }

    private void saveFindPositions(Matcher lineMatcher) {
        findStart = lineMatcher.start();
        findEnd = lineMatcher.end();
    }

    @Override
    public Iterator<CharBuffer> iterator() {
        return new MessageIterator();
    }

    @Override
    public void close() throws IOException {
        theFile.close();
    }

    private class MessageIterator implements Iterator<CharBuffer> {

        @Override
        public boolean hasNext() {
            if (!fromLineFound) {
                try {
                    close();
                } catch (IOException e) {
                    throw new RuntimeException("Exception closing file!");
                }
            }
            return fromLineFound;
        }

        /**
         * Returns a CharBuffer instance that contains a message between position and limit.
         * The array that backs this instance is the whole block of decoded messages.
         * @return CharBuffer instance
         */
        @Override
        public CharBuffer next() {
//            LOG.debug("next() called at offset {}", fromLineMathcer.start());
            final CharBuffer message;
            fromLineFound = fromLineMathcer.find();
            if (fromLineFound) {
                message = mboxCharBuffer.slice();
                message.position(findEnd + 1);
                saveFindPositions(fromLineMathcer);
                message.limit(fromLineMathcer.start());
            } else {
                /* We didn't find other From_ lines this means either:
                 *  - we reached end of mbox and no more messages
                 *  - we reached end of CharBuffer and need to decode another batch.
                 */
                if (byteBuffer.hasRemaining()) {
                    // decode another batch, but remember to copy the remaining chars first
                    CharBuffer oldData = mboxCharBuffer.duplicate();
                    mboxCharBuffer.clear();
                    oldData.position(findStart);
                    while (oldData.hasRemaining()) {
                        mboxCharBuffer.put(oldData.get());
                    }
                    try {
                        decodeNextCharBuffer();
                    } catch (CharConversionException ex) {
                        throw new RuntimeException(ex);
                    }
                    fromLineMathcer = MESSAGE_START.matcher(mboxCharBuffer);
                    fromLineFound = fromLineMathcer.find();
                    if (fromLineFound) {
                        saveFindPositions(fromLineMathcer);
                    }
                    message = mboxCharBuffer.slice();
                    message.position(fromLineMathcer.end() + 1);
                    fromLineFound = fromLineMathcer.find();
                    if (fromLineFound) {
                        saveFindPositions(fromLineMathcer);
                        message.limit(fromLineMathcer.start());
                    }
                } else {
                    message = mboxCharBuffer.slice();
                    message.position(findEnd + 1);
                    message.limit(message.capacity());
                }
            }
            return message;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    public static Builder fromFile(File filePath) {
        return new Builder(filePath);
    }

    public static Builder fromFile(String file) {
        return new Builder(file);
    }

    public static class Builder {

        private final File file;
        private Charset charset = Charsets.UTF_8;
        private String regexpPattern = FromLinePatterns.DEFAULT;
        private int flags = Pattern.MULTILINE;
        /** default max message size in chars: ~ 10MB chars. */
        private int maxMessageSize = 10 * 1024 * 1024;

        private Builder(String filePath) {
            this(new File(filePath));
        }

        private Builder(File file) {
            this.file = file;
        }

        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public Builder fromLine(String fromLine) {
            this.regexpPattern = fromLine;
            return this;
        }

        public Builder flags(int flags) {
            this.flags = flags;
            return this;
        }

        public Builder maxMessageSize(int maxMessageSize) {
            this.maxMessageSize = maxMessageSize;
            return this;
        }

        public MboxIterator build() throws FileNotFoundException, IOException {
            return new MboxIterator(file, charset, regexpPattern, flags, maxMessageSize);
        }
    }
}