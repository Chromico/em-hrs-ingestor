package uk.gov.hmcts.reform.em.hrs.ingestor.listener;

import com.microsoft.applicationinsights.TelemetryClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.em.hrs.ingestor.service.DefaultIngestorService;

@Component
public class IngestWhenApplicationReadyListener implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestWhenApplicationReadyListener.class);

    @Autowired
    private DefaultIngestorService defaultIngestorService;

    @Value("${toggle.cronjob}")
    private boolean enableCronjob;

    @Value("${toggle.shutdown}")
    boolean shouldShutDownAfterInitialIngestion;


    @Value("${ingestion.max-number-of-files-to-process-per-batch}")
    Integer maxNumberOfFilesToProcessPerBatch;


    @Autowired
    private TelemetryClient client;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {

        LOGGER.info("Enable Cronjob is set to {}", enableCronjob);
        LOGGER.info("maxNumberOfFilesToProcessPerBatch", maxNumberOfFilesToProcessPerBatch);

        if (client != null && client.getContext() != null) {
            String ik = client.getContext().getInstrumentationKey();
            LOGGER.info("Application Insights Key(4) = " + StringUtils.left(ik, 4));

        } else {
            LOGGER.info("No Application Insights Key");
        }

        if (enableCronjob) {
            try {
                LOGGER.info("Application Started {}\n...About to Ingest", event);
                defaultIngestorService.ingest();
                LOGGER.info("Initial Ingestion Complete", event);
            } catch (Exception e) {
                LOGGER.error("Unhandled Exception  during Ingestion - Aborted ... {}");
                e.printStackTrace();
            }

        } else {
            LOGGER.info("Application Not Starting as ENABLE_CRONJOB is false");
        }
        shutDownGracefully();
    }

    private void shutDownGracefully() {
        client.flush();

        if (shouldShutDownAfterInitialIngestion) {
            long millisToSleepForClientToFlush = 1000 * 10;
            try {
                Thread.sleep(millisToSleepForClientToFlush);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(0);
        }
    }
}
