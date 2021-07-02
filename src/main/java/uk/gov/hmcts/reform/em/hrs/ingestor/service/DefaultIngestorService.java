package uk.gov.hmcts.reform.em.hrs.ingestor.service;

import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.em.hrs.ingestor.exception.HrsApiException;
import uk.gov.hmcts.reform.em.hrs.ingestor.http.HrsApiClient;
import uk.gov.hmcts.reform.em.hrs.ingestor.model.CvpItem;
import uk.gov.hmcts.reform.em.hrs.ingestor.model.CvpItemSet;
import uk.gov.hmcts.reform.em.hrs.ingestor.model.HrsFileSet;
import uk.gov.hmcts.reform.em.hrs.ingestor.model.Metadata;
import uk.gov.hmcts.reform.em.hrs.ingestor.storage.CvpBlobstoreClient;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

@Component
public class DefaultIngestorService implements IngestorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIngestorService.class);
    private static int filesAttempted;
    private static int filesParsedOk;
    private static int filesSubmittedOk;
    private final CvpBlobstoreClient cvpBlobstoreClient;
    private final HrsApiClient hrsApiClient;
    private final IngestionFilterer ingestionFilterer;
    private final MetadataResolver metadataResolver;

    @Setter
    @Value("${ingestion.max-number-of-files-to-process-per-batch}")
    private Integer maxNumberOfFilesToProcessPerBatch = 100;

    @Autowired
    public DefaultIngestorService(final CvpBlobstoreClient cvpBlobstoreClient,
                                  final HrsApiClient hrsApiClient,
                                  final IngestionFilterer ingestionFilterer,
                                  final MetadataResolver metadataResolver) {
        this.cvpBlobstoreClient = cvpBlobstoreClient;
        this.hrsApiClient = hrsApiClient;
        this.ingestionFilterer = ingestionFilterer;
        this.metadataResolver = metadataResolver;
    }

    @Override
    public void ingest() {
        ingest(maxNumberOfFilesToProcessPerBatch);
    }

    @Override
    public void ingest(Integer maxNumberOfFiles) {
        filesAttempted = 0;
        filesParsedOk = 0;
        filesSubmittedOk = 0;
        LOGGER.info("Ingestion Started with BATCH PROCESSING LIMIT of {}", maxNumberOfFiles);
        final Set<String> folders = cvpBlobstoreClient.getFolders();
        LOGGER.info("Folders found in CVP {} ", folders.size());
        folders.forEach(folder -> {
            if (batchProcessingLimitReached(maxNumberOfFiles)) {
                return;
            }

            LOGGER.info("Inspecting folder: {}", folder);
            final Set<CvpItem> filteredSet = getFilesToIngest(folder);
            LOGGER.info("filterSet size: {}", filteredSet.size());
            filteredSet.forEach(file -> {
                if (batchProcessingLimitReached(maxNumberOfFiles)) {
                    return;
                }
                filesAttempted++;
                resolveMetaDataAndPostFileToHrs(file);
            });
            LOGGER.info("Running Total of Files Attempted: {}", filesAttempted);

        });
        LOGGER.info("Ingestion Complete");
        if (batchProcessingLimitReached(maxNumberOfFiles)) {
            LOGGER.info("Batch Processing Limit Reached ({})", maxNumberOfFiles);
        }
        LOGGER.info("Total files Attempted: {}", filesAttempted);
        LOGGER.info("Total files Parsed Ok: {}", filesParsedOk);
        LOGGER.info("Total files Submitted Ok: {}", filesSubmittedOk);

    }

    private void resolveMetaDataAndPostFileToHrs(CvpItem file) {
        try {
            LOGGER.info("Resolving Filename {}", file.getFilename());
            final Metadata metaData = metadataResolver.resolve(file);
            filesParsedOk++;
            hrsApiClient.postFile(metaData);
            filesSubmittedOk++;
        } catch (HrsApiException hrsApi) {
            LOGGER.error(
                "Response error: {} => {} => {}",
                hrsApi.getCode(),
                hrsApi.getMessage(),
                hrsApi.getBody()
            );
        } catch (Exception e) {
            LOGGER.error(
                "Exception processing file {}:: ",
                file.getFilename(),
                e
            ); // TODO: covered by EM-3582
        }
    }

    private boolean batchProcessingLimitReached(Integer maxNumberOfFiles) {
        return filesAttempted >= maxNumberOfFiles;
    }

    private Set<CvpItem> getFilesToIngest(final String folder) {
        try {
            LOGGER.info("Getting CVP files in folder");
            final CvpItemSet cvpItemSet = cvpBlobstoreClient.findByFolder(folder);
            LOGGER.info("Getting HRS files already ingested");
            final HrsFileSet hrsFileSet = hrsApiClient.getIngestedFiles(folder);
            LOGGER.info("Filtering out files not required from original cvp list");
            Set<CvpItem> filesToIngest = ingestionFilterer.filter(cvpItemSet, hrsFileSet);

            int cvpFilesCount = cvpItemSet.getCvpItems().size();
            int hrsFileCount = hrsFileSet.getHrsFiles().size();
            int filesToIngestCount = filesToIngest.size();

            LOGGER.info("Folder:{}, CVP Files:{}, HRS Files:{}, To Ingest:{}",
                        folder, cvpFilesCount, hrsFileCount, filesToIngestCount
            );
            return filesToIngest;
        } catch (HrsApiException | IOException e) {
            LOGGER.error("", e); // TODO: covered by EM-3582
            return Collections.emptySet();
        }
    }

}
