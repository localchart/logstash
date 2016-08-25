package org.logstash.ackedqueue;

import org.junit.Test;
import org.logstash.common.io.ByteBufferPageIO;

import java.io.IOException;
import java.util.*;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class QueueTest {

    @Test
    public void newQueue() throws IOException {
        Queue q = new TestQueue(TestSettings.getSettings(10));
        q.open();

        assertThat(q.nonBlockReadBatch(1), is(equalTo(null)));
    }

    @Test
    public void singleWriteRead() throws IOException {
        Queue q = new TestQueue(TestSettings.getSettings(100));
        q.open();

        Queueable element = new StringElement("foobarbaz");
        q.write(element);

        Batch b = q.nonBlockReadBatch(1);

        assertThat(b.getElements().size(), is(equalTo(1)));
        assertThat(b.getElements().get(0).toString(), is(equalTo(element.toString())));
        assertThat(q.nonBlockReadBatch(1), is(equalTo(null)));
    }

    @Test
    public void singleWriteMultiRead() throws IOException {
        Queue q = new TestQueue(TestSettings.getSettings(100));
        q.open();

        Queueable element = new StringElement("foobarbaz");
        q.write(element);

        Batch b = q.nonBlockReadBatch(2);

        assertThat(b.getElements().size(), is(equalTo(1)));
        assertThat(b.getElements().get(0).toString(), is(equalTo(element.toString())));
        assertThat(q.nonBlockReadBatch(2), is(equalTo(null)));
    }

    @Test
    public void multiWriteSamePage() throws IOException {
        Queue q = new TestQueue(TestSettings.getSettings(100));
        q.open();

        List<Queueable> elements = Arrays.asList(new StringElement("foobarbaz1"), new StringElement("foobarbaz2"), new StringElement("foobarbaz3"));

        for (Queueable e : elements) {
            q.write(e);
        }

        Batch b = q.nonBlockReadBatch(2);

        assertThat(b.getElements().size(), is(equalTo(2)));
        assertThat(b.getElements().get(0).toString(), is(equalTo(elements.get(0).toString())));
        assertThat(b.getElements().get(1).toString(), is(equalTo(elements.get(1).toString())));

        b = q.nonBlockReadBatch(2);

        assertThat(b.getElements().size(), is(equalTo(1)));
        assertThat(b.getElements().get(0).toString(), is(equalTo(elements.get(2).toString())));
    }

    @Test
    public void writeMultiPage() throws IOException {
        List<Queueable> elements = Arrays.asList(new StringElement("foobarbaz1"), new StringElement("foobarbaz2"), new StringElement("foobarbaz3"), new StringElement("foobarbaz4"));
        int singleElementCapacity = ByteBufferPageIO.HEADER_SIZE + ByteBufferPageIO._persistedByteCount(elements.get(0).serialize().length);

        TestQueue q = new TestQueue(TestSettings.getSettings(2 * singleElementCapacity));
        q.open();

        for (Queueable e : elements) {
            q.write(e);
        }

        // total of 2 pages: 1 head and 1 tail
        assertThat(q.getTailPages().size(), is(equalTo(1)));

        assertThat(q.getTailPages().get(0).isFullyRead(), is(equalTo(false)));
        assertThat(q.getTailPages().get(0).isFullyAcked(), is(equalTo(false)));
        assertThat(q.getHeadPage().isFullyRead(), is(equalTo(false)));
        assertThat(q.getHeadPage().isFullyAcked(), is(equalTo(false)));

        Batch b = q.nonBlockReadBatch(10);
        assertThat(b.getElements().size(), is(equalTo(2)));

        assertThat(q.getTailPages().size(), is(equalTo(1)));

        assertThat(q.getTailPages().get(0).isFullyRead(), is(equalTo(true)));
        assertThat(q.getTailPages().get(0).isFullyAcked(), is(equalTo(false)));
        assertThat(q.getHeadPage().isFullyRead(), is(equalTo(false)));
        assertThat(q.getHeadPage().isFullyAcked(), is(equalTo(false)));

        b = q.nonBlockReadBatch(10);
        assertThat(b.getElements().size(), is(equalTo(2)));

        assertThat(q.getTailPages().get(0).isFullyRead(), is(equalTo(true)));
        assertThat(q.getTailPages().get(0).isFullyAcked(), is(equalTo(false)));
        assertThat(q.getHeadPage().isFullyRead(), is(equalTo(true)));
        assertThat(q.getHeadPage().isFullyAcked(), is(equalTo(false)));

        b = q.nonBlockReadBatch(10);
        assertThat(b, is(equalTo(null)));
    }


    @Test
    public void writeMultiPageWithInOrderAcking() throws IOException {
        List<Queueable> elements = Arrays.asList(new StringElement("foobarbaz1"), new StringElement("foobarbaz2"), new StringElement("foobarbaz3"), new StringElement("foobarbaz4"));
        int singleElementCapacity = ByteBufferPageIO.HEADER_SIZE + ByteBufferPageIO._persistedByteCount(elements.get(0).serialize().length);

        TestQueue q = new TestQueue(TestSettings.getSettings(2 * singleElementCapacity));
        q.open();

        for (Queueable e : elements) {
            q.write(e);
        }

        Batch b = q.nonBlockReadBatch(10);

        assertThat(b.getElements().size(), is(equalTo(2)));
        assertThat(q.getTailPages().size(), is(equalTo(1)));

        // lets keep a ref to that tail page before acking
        BeheadedPage tailPage = q.getTailPages().get(0);

        assertThat(tailPage.isFullyRead(), is(equalTo(true)));

        // ack first batch which includes all elements from tailpage
        b.close();

        assertThat(q.getTailPages().size(), is(equalTo(0)));
        assertThat(tailPage.isFullyRead(), is(equalTo(true)));
        assertThat(tailPage.isFullyAcked(), is(equalTo(true)));

        b = q.nonBlockReadBatch(10);

        assertThat(b.getElements().size(), is(equalTo(2)));
        assertThat(q.getHeadPage().isFullyRead(), is(equalTo(true)));
        assertThat(q.getHeadPage().isFullyAcked(), is(equalTo(false)));

        b.close();

        assertThat(q.getHeadPage().isFullyAcked(), is(equalTo(true)));
    }

    @Test
    public void writeMultiPageWithInOrderAckingCheckpoints() throws IOException {
        List<Queueable> elements1 = Arrays.asList(new StringElement("foobarbaz1"), new StringElement("foobarbaz2"));
        List<Queueable> elements2 = Arrays.asList(new StringElement("foobarbaz3"), new StringElement("foobarbaz4"));
        int singleElementCapacity = ByteBufferPageIO.HEADER_SIZE + ByteBufferPageIO._persistedByteCount(elements1.get(0).serialize().length);

        Settings settings = TestSettings.getSettings(2 * singleElementCapacity);
        TestQueue q = new TestQueue(settings);
        q.open();

        assertThat(q.getHeadPage().getPageNum(), is(equalTo(0)));
        Checkpoint c = q.getCheckpointIO().read("checkpoint.head");
        assertThat(c.getPageNum(), is(equalTo(0)));
        assertThat(c.getElementCount(), is(equalTo(0)));
        assertThat(c.getMinSeqNum(), is(equalTo(0L)));
        assertThat(c.getFirstUnackedSeqNum(), is(equalTo(0L)));
        assertThat(c.getFirstUnackedPageNum(), is(equalTo(0)));

        for (Queueable e : elements1) {
            q.write(e);
        }

        c = q.getCheckpointIO().read("checkpoint.head");
        assertThat(c.getPageNum(), is(equalTo(0)));
        assertThat(c.getElementCount(), is(equalTo(0)));
        assertThat(c.getMinSeqNum(), is(equalTo(0L)));
        assertThat(c.getFirstUnackedSeqNum(), is(equalTo(0L)));
        assertThat(c.getFirstUnackedPageNum(), is(equalTo(0)));

        assertThat(elements1.get(1).getSeqNum(), is(equalTo(2L)));
        q.ensurePersistedUpto(2);

        c = q.getCheckpointIO().read("checkpoint.head");
        assertThat(c.getPageNum(), is(equalTo(0)));
        assertThat(c.getElementCount(), is(equalTo(2)));
        assertThat(c.getMinSeqNum(), is(equalTo(1L)));
        assertThat(c.getFirstUnackedSeqNum(), is(equalTo(1L)));
        assertThat(c.getFirstUnackedPageNum(), is(equalTo(0)));

        for (Queueable e : elements2) {
            q.write(e);
        }

        c = q.getCheckpointIO().read("checkpoint.head");
        assertThat(c.getPageNum(), is(equalTo(1)));
        assertThat(c.getElementCount(), is(equalTo(0)));
        assertThat(c.getMinSeqNum(), is(equalTo(0L)));
        assertThat(c.getFirstUnackedSeqNum(), is(equalTo(0L)));
        assertThat(c.getFirstUnackedPageNum(), is(equalTo(0)));

        c = q.getCheckpointIO().read("checkpoint.0");
        assertThat(c.getPageNum(), is(equalTo(0)));
        assertThat(c.getElementCount(), is(equalTo(2)));
        assertThat(c.getMinSeqNum(), is(equalTo(1L)));
        assertThat(c.getFirstUnackedSeqNum(), is(equalTo(1L)));

        Batch b = q.nonBlockReadBatch(10);
        b.close();

        assertThat(q.getCheckpointIO().read("checkpoint.0"), is(nullValue()));

        c = q.getCheckpointIO().read("checkpoint.head");
        assertThat(c.getPageNum(), is(equalTo(1)));
        assertThat(c.getElementCount(), is(equalTo(2)));
        assertThat(c.getMinSeqNum(), is(equalTo(3L)));
        assertThat(c.getFirstUnackedSeqNum(), is(equalTo(3L)));
        assertThat(c.getFirstUnackedPageNum(), is(equalTo(1)));

        b = q.nonBlockReadBatch(10);
        b.close();

        c = q.getCheckpointIO().read("checkpoint.head");
        assertThat(c.getPageNum(), is(equalTo(1)));
        assertThat(c.getElementCount(), is(equalTo(2)));
        assertThat(c.getMinSeqNum(), is(equalTo(3L)));
        assertThat(c.getFirstUnackedSeqNum(), is(equalTo(5L)));
        assertThat(c.getFirstUnackedPageNum(), is(equalTo(1)));
    }

    @Test
    public void randomAcking() throws IOException {
        Random random = new Random();

        // 10 tests of random queue sizes
        for (int loop = 0; loop < 10; loop++) {
            int page_count = random.nextInt(10000) + 1;
            int digits = new Double(Math.ceil(Math.log10(page_count))).intValue();

            // create a queue with a single element per page
            List<Queueable> elements = new ArrayList<>();
            for (int i = 0; i < page_count; i++) {
                elements.add(new StringElement(String.format("%0" + digits + "d", i)));
            }
            int singleElementCapacity = ByteBufferPageIO.HEADER_SIZE + ByteBufferPageIO._persistedByteCount(elements.get(0).serialize().length);

            TestQueue q = new TestQueue(TestSettings.getSettings(singleElementCapacity));
            q.open();

            for (Queueable e : elements) {
                q.write(e);
            }

            assertThat(q.getTailPages().size(), is(equalTo(page_count - 1)));

            // first read all elements
            List<Batch> batches = new ArrayList<>();
            for (Batch b = q.nonBlockReadBatch(1); b != null; b = q.nonBlockReadBatch(1)) {
                batches.add(b);
            }
            assertThat(batches.size(), is(equalTo(page_count)));

            // then ack randomly
            Collections.shuffle(batches);
            for (Batch b : batches) {
                b.close();
            }

            assertThat(q.getTailPages().size(), is(equalTo(0)));
        }
    }

}