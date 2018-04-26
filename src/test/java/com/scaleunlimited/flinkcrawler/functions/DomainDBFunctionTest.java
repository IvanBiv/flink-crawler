package com.scaleunlimited.flinkcrawler.functions;

import static org.junit.Assert.assertFalse;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.streaming.api.operators.StreamFlatMap;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scaleunlimited.flinkcrawler.pojos.CrawlStateUrl;
import com.scaleunlimited.flinkcrawler.pojos.RawUrl;
import com.scaleunlimited.flinkcrawler.pojos.UrlType;
import com.scaleunlimited.flinkcrawler.utils.FlinkUtils;

public class DomainDBFunctionTest {
    static final Logger LOGGER = LoggerFactory.getLogger(DomainDBFunctionTest.class);

    private static final int NUM_INPUT_URLS = 100;
    private static final int MAX_PARALLELISM = 10;

    PldKeySelector<CrawlStateUrl> _pldKeySelector;
    OneInputStreamOperatorTestHarness<CrawlStateUrl, CrawlStateUrl> _testHarnesses[];

    @Before
    public void setUp() throws Exception {
        _pldKeySelector = new PldKeySelector<CrawlStateUrl>();
    }

    @Test
    public void testDemo() throws Throwable {
        KeyedOneInputStreamOperatorTestHarness<String, CrawlStateUrl, CrawlStateUrl> testHarness =
            new KeyedOneInputStreamOperatorTestHarness<String, CrawlStateUrl, CrawlStateUrl>(
                new StreamFlatMap<>(new DomainDBFunction()),
                new PldKeySelector<CrawlStateUrl>(),
                BasicTypeInfo.STRING_TYPE_INFO,
                1,
                1,
                0);
        testHarness.setup();
        testHarness.open();

        for (int i = 0; i < 10; i++) {
            String urlString = String.format("https://domain-%d.com/page1", i);
            CrawlStateUrl url = new CrawlStateUrl(new RawUrl(urlString));
            testHarness.processElement(new StreamRecord<>(url));
        }
        testHarness.snapshot(0L, 0L);
    }
        

    @Test
    public void test() throws Throwable {
            
        LOGGER.info("Set up a parallelism of 1");
        _testHarnesses = makeTestHarnesses(1, null);
        
        LOGGER.info("Processing input URLs...");
        List<CrawlStateUrl> inputUrls = makeInputUrls();
        processUrls(inputUrls);
        
        LOGGER.info("Stop and then restart with parallelism of 2");
        OperatorSubtaskState savedState = _testHarnesses[0].snapshot(0L, 0L);
        closeTestHarnesses();
        _testHarnesses = makeTestHarnesses(2, savedState);
        
        LOGGER.info("Processing the same input URLs again...");
        processUrls(inputUrls);
        
        LOGGER.info("Processing tickler URLs...");
        processTicklerUrls();
        
        // Grab the domain ticklers that come out
        List<CrawlStateUrl> outputDomains0 = getOutputDomains(0);
        List<CrawlStateUrl> outputDomains1 = getOutputDomains(1);
        closeTestHarnesses();
        
        // There should be no overlap and we should have a complete set
//        assertEquals(   NUM_INPUT_URLS, 
//                        (outputDomains0.size() + outputDomains1.size()));
        for (CrawlStateUrl url : outputDomains0) {
            assertFalse(outputDomains1.contains(url));
        }
        for (CrawlStateUrl url : outputDomains1) {
            assertFalse(outputDomains0.contains(url));
        }
    }

    private List<CrawlStateUrl> getOutputDomains(int subTaskIndex) {
        List<StreamRecord<? extends CrawlStateUrl>> outputStreamRecords =
            _testHarnesses[subTaskIndex].extractOutputStreamRecords();
        List<CrawlStateUrl> result = new ArrayList<CrawlStateUrl>();
        for (StreamRecord<? extends CrawlStateUrl> record : outputStreamRecords) {
            CrawlStateUrl url = record.getValue();
            if (url.getUrlType() == UrlType.DOMAIN) {
                result.add(url);
            }
        }
        return result;
    }

    private ArrayList<CrawlStateUrl> makeInputUrls() throws MalformedURLException {
        ArrayList<CrawlStateUrl> result = new ArrayList<CrawlStateUrl>();
        for (int i = 0; i < NUM_INPUT_URLS; i++) {
            String urlString = String.format("https://domain-%d.com/page1", i);
            CrawlStateUrl url = new CrawlStateUrl(new RawUrl(urlString));
            result.add(url);
        }
        return result;
    }

    private void processUrls(List<CrawlStateUrl> urls) throws Exception {
        int parallelism = _testHarnesses.length;
        for (CrawlStateUrl url : urls) {
            String key = _pldKeySelector.getKey(url);
            int subTaskIndex = FlinkUtils.getOperatorIndexForKey(key, MAX_PARALLELISM, parallelism);
            _testHarnesses[subTaskIndex].processElement(new StreamRecord<>(url));
        }
    }

    private void processTicklerUrls() throws Exception {
        int parallelism = _testHarnesses.length;
        for (int i = 0; i < parallelism; i++) {
            CrawlStateUrl ticklerUrl = 
                CrawlStateUrl.makeTicklerUrl(MAX_PARALLELISM, parallelism, i);
            _testHarnesses[i].processElement(new StreamRecord<>(ticklerUrl));
        }
    }

    private OneInputStreamOperatorTestHarness<CrawlStateUrl, CrawlStateUrl>[] makeTestHarnesses(
        int parallelism, 
        OperatorSubtaskState savedState)
        throws Exception {
        
        @SuppressWarnings("unchecked")
        OneInputStreamOperatorTestHarness<CrawlStateUrl, CrawlStateUrl> result[] = 
            new OneInputStreamOperatorTestHarness[parallelism];
        
        for (int i = 0; i < parallelism; i++) {
            result[i] = makeTestHarness(parallelism, i, savedState);
        }
        return result;
    }
    
    private void closeTestHarnesses() throws Exception {
        for (OneInputStreamOperatorTestHarness<CrawlStateUrl, CrawlStateUrl> harness : _testHarnesses) {
            harness.close();
        }
    }

    private OneInputStreamOperatorTestHarness<CrawlStateUrl, CrawlStateUrl> makeTestHarness(
        int parallelism, 
        int subTaskIndex, 
        OperatorSubtaskState savedState)
        throws Exception {
        
        OneInputStreamOperatorTestHarness<CrawlStateUrl, CrawlStateUrl> result =
            new KeyedOneInputStreamOperatorTestHarness<String, CrawlStateUrl, CrawlStateUrl>(
                    new StreamFlatMap<>(new DomainDBFunction()),
                    _pldKeySelector,
                    BasicTypeInfo.STRING_TYPE_INFO,
                    MAX_PARALLELISM,
                    parallelism,
                    subTaskIndex);
        result.setStateBackend(new MemoryStateBackend());
        result.setup();
        result.open();
        if (savedState != null) {
            result.initializeState(savedState);
        }
        return result;
    }
}
