package it.pagopa.pn.delivery.svc.search;

import it.pagopa.pn.commons.exceptions.PnInternalException;
import it.pagopa.pn.delivery.PnDeliveryConfigs;
import it.pagopa.pn.delivery.generated.openapi.clients.mandate.model.DelegateType;
import it.pagopa.pn.delivery.generated.openapi.clients.mandate.model.InternalMandateDto;
import it.pagopa.pn.delivery.generated.openapi.clients.mandate.model.MandateByDelegatorRequestDto;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationSearchRow;
import it.pagopa.pn.delivery.middleware.NotificationDao;
import it.pagopa.pn.delivery.middleware.notificationdao.EntityToDtoNotificationMetadataMapper;
import it.pagopa.pn.delivery.middleware.notificationdao.entities.NotificationDelegationMetadataEntity;
import it.pagopa.pn.delivery.models.InputSearchNotificationDelegatedDto;
import it.pagopa.pn.delivery.models.PageSearchTrunk;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.pnclient.datavault.PnDataVaultClientImpl;
import it.pagopa.pn.delivery.pnclient.mandate.PnMandateClientImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static it.pagopa.pn.delivery.exception.PnDeliveryExceptionCodes.ERROR_CODE_DELIVERY_UNSUPPORTED_NOTIFICATION_METADATA;

@Slf4j
public class NotificationDelegatedSearchMultiPage extends NotificationSearch {

    public static final int FILTER_EXPRESSION_APPLIED_MULTIPLIER = 4;
    public static final int MAX_DYNAMO_SIZE = 2000;

    private final NotificationDao notificationDao;
    private final PnLastEvaluatedKey lastEvaluatedKey;
    private final InputSearchNotificationDelegatedDto searchDto;
    private final PnDeliveryConfigs cfg;
    private final IndexNameAndPartitions indexNameAndPartitions;
    private final PnMandateClientImpl mandateClient;

    public NotificationDelegatedSearchMultiPage(NotificationDao notificationDao,
                                                EntityToDtoNotificationMetadataMapper entityToDto,
                                                InputSearchNotificationDelegatedDto searchDto,
                                                PnLastEvaluatedKey lastEvaluatedKey,
                                                PnDeliveryConfigs cfg,
                                                PnDataVaultClientImpl dataVaultClient,
                                                PnMandateClientImpl mandateClient,
                                                IndexNameAndPartitions indexNameAndPartitions) {
        super(dataVaultClient, entityToDto);
        this.notificationDao = notificationDao;
        this.searchDto = searchDto;
        this.lastEvaluatedKey = lastEvaluatedKey;
        this.cfg = cfg;
        this.mandateClient = mandateClient;
        this.indexNameAndPartitions = indexNameAndPartitions;
    }

    @Override
    public ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> searchNotificationMetadata() {
        log.info("notification delegated paged search indexName={}", indexNameAndPartitions.getIndexName());

        Integer maxPageNumber = searchDto.getMaxPageNumber() != null ? searchDto.getMaxPageNumber() : cfg.getMaxPageSize();

        int requiredSize = searchDto.getSize() * maxPageNumber + 1;
        int dynamoDbPageSize = requiredSize;
        if (!CollectionUtils.isEmpty(searchDto.getStatuses())
                || StringUtils.hasText(searchDto.getSenderId())
                || StringUtils.hasText(searchDto.getReceiverId())) {
            dynamoDbPageSize = dynamoDbPageSize * FILTER_EXPRESSION_APPLIED_MULTIPLIER;
        }

        int logItemCount = 0;

        List<NotificationDelegationMetadataEntity> dataRead = new ArrayList<>();
        int startIndex = 0;
        PnLastEvaluatedKey startEvaluedKey = null;
        if (lastEvaluatedKey != null) {
            startEvaluedKey = lastEvaluatedKey;
            startIndex = indexNameAndPartitions.getPartitions().indexOf(lastEvaluatedKey.getExternalLastEvaluatedKey());
            log.debug("lastEvaluatedKey is not null, starting search from index={}", startIndex);
        }

        for (int pIdx = startIndex; pIdx < indexNameAndPartitions.getPartitions().size(); pIdx++) {
            String currentPartition = indexNameAndPartitions.getPartitions().get(pIdx);
            logItemCount += readDataFromPartition(1, currentPartition, dataRead, startEvaluedKey, requiredSize, dynamoDbPageSize);
            startEvaluedKey = null;
            if (dataRead.size() >= requiredSize) {
                log.debug("reached required size, ending search");
                break;
            }
        }

        log.info("search request completed, totalDbQueryCount={} totalRowRead={}", logItemCount, dataRead.size());
        // necessario controllare le deleghe per pulire eventuali dati sporchi
        List<NotificationDelegationMetadataEntity> filtered = checkMandates(dataRead);
        log.info("post filter mandates completed, preCheckCount={} postCheckCount={}", dataRead.size(), filtered.size());
        return prepareGlobalResult(filtered, dataRead.size(), requiredSize);
    }

    private int readDataFromPartition(int currentRequest, String partition, List<NotificationDelegationMetadataEntity> cumulativeQueryResult,
                                      PnLastEvaluatedKey lastEvaluatedKey,
                                      int requiredSize, int dynamoDbPageSize) {
        log.debug("START compute partition read trunk partition={} indexName={} currentRequest={} dynamoDbPageSize={}",
                partition, indexNameAndPartitions.getIndexName(), currentRequest, dynamoDbPageSize);

        PageSearchTrunk<NotificationDelegationMetadataEntity> oneQueryResult =
                notificationDao.searchDelegatedForOneMonth(searchDto,
                        indexNameAndPartitions.getIndexName(),
                        partition,
                        dynamoDbPageSize,
                        lastEvaluatedKey);
        log.debug("END search for one month indexName={} partitionValue={} dynamoDbPageSize={}",
                indexNameAndPartitions.getIndexName(), partition, dynamoDbPageSize);

        if (!CollectionUtils.isEmpty(oneQueryResult.getResults())) {
            cumulativeQueryResult.addAll(oneQueryResult.getResults());
        }

        if (cumulativeQueryResult.size() >= requiredSize) {
            log.debug("ending search, requiredSize reached - partition={} currentRequest={}", partition, currentRequest);
            return currentRequest;
        }

        if (oneQueryResult.getLastEvaluatedKey() != null) {
            log.debug("There are more data to read for partition={} currentRequest={} currentReadSize={}",
                    partition, currentRequest, cumulativeQueryResult.size());
            PnLastEvaluatedKey nextEvaluationKeyForSearch = new PnLastEvaluatedKey();
            nextEvaluationKeyForSearch.setExternalLastEvaluatedKey(partition);
            nextEvaluationKeyForSearch.setInternalLastEvaluatedKey(oneQueryResult.getLastEvaluatedKey());

            float multiplier = 2 - Math.min(oneQueryResult.getResults().size() / (float) requiredSize, 1);
            dynamoDbPageSize = Math.round(dynamoDbPageSize * multiplier);
            dynamoDbPageSize = Math.min(dynamoDbPageSize, MAX_DYNAMO_SIZE);

            return readDataFromPartition(currentRequest + 1, partition, cumulativeQueryResult, nextEvaluationKeyForSearch, requiredSize, dynamoDbPageSize);
        } else {
            log.debug("no more data to read for partition={} currentRequest={} currentReadSize={}", partition, currentRequest, cumulativeQueryResult.size());
            return currentRequest;
        }
    }

    private ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> prepareGlobalResult(List<NotificationDelegationMetadataEntity> queryResult,
                                                                                               int preFilterSize,
                                                                                               int requiredSize) {
        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> globalResult = new ResultPaginationDto<>();
        globalResult.setNextPagesKey(new ArrayList<>());

        globalResult.setResultsPage(queryResult.stream()
                .limit(searchDto.getSize())
                .map(metadata -> {
                    try {
                        return entityToDto.entity2Dto(metadata);
                    } catch (Exception e) {
                        String msg = String.format("Exception in mapping result for notification delegation metadata pk=%s", metadata.getIunRecipientIdDelegateIdGroupId());
                        throw new PnInternalException(msg, ERROR_CODE_DELIVERY_UNSUPPORTED_NOTIFICATION_METADATA, e);
                    }
                })
                .toList());

        globalResult.setMoreResult(preFilterSize >= requiredSize);

        for (int i = 1; i <= cfg.getMaxPageSize(); i++) {
            int index = searchDto.getSize() * i;
            if (queryResult.size() <= index) {
                break;
            }
            PnLastEvaluatedKey pageLastEvaluatedKey = new PnLastEvaluatedKey();
            NotificationDelegationMetadataEntity keyElement = queryResult.get(index - 1);
            if (indexNameAndPartitions.getIndexName().equals(IndexNameAndPartitions.SearchIndexEnum.INDEX_BY_DELEGATE_GROUP)) {
                pageLastEvaluatedKey.setExternalLastEvaluatedKey(keyElement.getDelegateIdGroupIdCreationMonth());
                pageLastEvaluatedKey.setInternalLastEvaluatedKey(Map.of(
                        NotificationDelegationMetadataEntity.FIELD_IUN_RECIPIENT_ID_DELEGATE_ID_GROUP_ID, AttributeValue.builder().s(keyElement.getIunRecipientIdDelegateIdGroupId()).build(),
                        NotificationDelegationMetadataEntity.FIELD_DELEGATE_ID_GROUP_ID_CREATION_MONTH, AttributeValue.builder().s(keyElement.getDelegateIdGroupIdCreationMonth()).build(),
                        NotificationDelegationMetadataEntity.FIELD_SENT_AT, AttributeValue.builder().s(keyElement.getSentAt().toString()).build()
                ));
            } else if (indexNameAndPartitions.getIndexName().equals(IndexNameAndPartitions.SearchIndexEnum.INDEX_BY_DELEGATE)) {
                pageLastEvaluatedKey.setExternalLastEvaluatedKey(keyElement.getDelegateIdCreationMonth());
                pageLastEvaluatedKey.setInternalLastEvaluatedKey(Map.of(
                        NotificationDelegationMetadataEntity.FIELD_IUN_RECIPIENT_ID_DELEGATE_ID_GROUP_ID, AttributeValue.builder().s(keyElement.getIunRecipientIdDelegateIdGroupId()).build(),
                        NotificationDelegationMetadataEntity.FIELD_DELEGATE_ID_CREATION_MONTH, AttributeValue.builder().s(keyElement.getDelegateIdCreationMonth()).build(),
                        NotificationDelegationMetadataEntity.FIELD_SENT_AT, AttributeValue.builder().s(keyElement.getSentAt().toString()).build()
                ));
            }
            globalResult.getNextPagesKey().add(pageLastEvaluatedKey);
        }

        deanonimizeResults(globalResult);

        return globalResult;
    }

    private List<NotificationDelegationMetadataEntity> checkMandates(List<NotificationDelegationMetadataEntity> queryResult) {
        if (CollectionUtils.isEmpty(queryResult)) {
            log.debug("skip check mandates - query result is empty");
            return queryResult;
        }
        List<InternalMandateDto> mandates = getMandates(queryResult);
        if (mandates.isEmpty()) {
            log.info("no valid mandate found");
            return Collections.emptyList();
        }
        Map<String, InternalMandateDto> mapMandates = mandates.stream()
                .collect(Collectors.toMap(InternalMandateDto::getMandateId, Function.identity()));
        return queryResult.stream()
                .filter(row -> isMandateValid(mapMandates.get(row.getMandateId()), row))
                .toList();
    }

    private boolean isMandateValid(InternalMandateDto mandate, NotificationDelegationMetadataEntity entity) {
        if (mandate == null) {
            return false;
        }
        Instant mandateStartDate = mandate.getDatefrom() != null ? Instant.parse(mandate.getDatefrom()) : null;
        Instant mandateEndDate = mandate.getDateto() != null ? Instant.parse(mandate.getDateto()) : null;
        return entity.getRecipientId().equals(mandate.getDelegator())
                && (mandateStartDate == null || entity.getSentAt().compareTo(mandateStartDate) >= 0) // sent after start mandate
                && (mandateEndDate == null || entity.getSentAt().compareTo(mandateEndDate) <= 0) // sent before end mandate
                && (CollectionUtils.isEmpty(mandate.getVisibilityIds()) || mandate.getVisibilityIds().contains(entity.getSenderId()));
    }

    private List<InternalMandateDto> getMandates(List<NotificationDelegationMetadataEntity> queryResult) {
        List<MandateByDelegatorRequestDto> requestBody = queryResult.stream()
                .map(row -> {
                    MandateByDelegatorRequestDto requestDto = new MandateByDelegatorRequestDto();
                    requestDto.setMandateId(row.getMandateId());
                    requestDto.setDelegatorId(row.getRecipientId());
                    return requestDto;
                })
                .distinct()
                .toList();
        return mandateClient.listMandatesByDelegators(DelegateType.PG, searchDto.getCxGroups(), requestBody);
    }

}
