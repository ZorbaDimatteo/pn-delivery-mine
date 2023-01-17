package it.pagopa.pn.delivery.middleware.notificationdao;

import it.pagopa.pn.commons.abstractions.impl.AbstractDynamoKeyValueStore;
import it.pagopa.pn.commons.exceptions.PnIdConflictException;
import it.pagopa.pn.delivery.PnDeliveryConfigs;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationStatus;
import it.pagopa.pn.delivery.middleware.notificationdao.entities.NotificationDelegationMetadataEntity;
import it.pagopa.pn.delivery.models.InputSearchNotificationDelegatedDto;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.PageSearchTrunk;
import it.pagopa.pn.delivery.svc.search.PnLastEvaluatedKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class NotificationDelegationMetadataEntityDaoDynamo
        extends AbstractDynamoKeyValueStore<NotificationDelegationMetadataEntity>
        implements NotificationDelegationMetadataEntityDao {

    protected NotificationDelegationMetadataEntityDaoDynamo(DynamoDbEnhancedClient dynamoDbEnhancedClient,
                                                            PnDeliveryConfigs cfg) {
        super(dynamoDbEnhancedClient.table(tableName(cfg), TableSchema.fromClass(NotificationDelegationMetadataEntity.class)));
    }

    private static String tableName(PnDeliveryConfigs cfg) {
        return cfg.getNotificationDelegationMetadataDao().getTableName();
    }

    @Override
    public void putIfAbsent(NotificationDelegationMetadataEntity entity) throws PnIdConflictException {
        PutItemEnhancedRequest<NotificationDelegationMetadataEntity> request = PutItemEnhancedRequest
                .builder(NotificationDelegationMetadataEntity.class)
                .item(entity)
                .build();
        table.putItem(request);
    }

    @Override
    public PageSearchTrunk<NotificationDelegationMetadataEntity> searchForOneMonth(InputSearchNotificationDelegatedDto searchDto,
                                                                                   String indexName,
                                                                                   String partitionValue,
                                                                                   int size,
                                                                                   PnLastEvaluatedKey lastEvaluatedKey) {
        log.debug("START search for one month");
        Instant startDate = searchDto.getStartDate();
        Instant endDate = searchDto.getEndDate();

        Key.Builder builder = Key.builder().partitionValue(partitionValue);
        Key key1 = builder.sortValue(startDate.toString()).build();
        Key key2 = builder.sortValue(endDate.toString()).build();
        log.debug("key building done pk={} start-sk={} end-sk={}", key1.partitionKeyValue(), key1.sortKeyValue(), key2.sortKeyValue());

        QueryConditional betweenConditional = QueryConditional.sortBetween(key1, key2);

        DynamoDbIndex<NotificationDelegationMetadataEntity> index = table.index(indexName);

        QueryEnhancedRequest.Builder requestBuilder = QueryEnhancedRequest.builder();

        requestBuilder.queryConditional(betweenConditional)
                .limit(size)
                .scanIndexForward(false);

        addFilterExpression(searchDto, requestBuilder);

        log.debug("START query execution");
        SdkIterable<Page<NotificationDelegationMetadataEntity>> pages = index.query(requestBuilder.build());
        log.debug("END query execution");

        Page<NotificationDelegationMetadataEntity> page = pages.iterator().next();

        PageSearchTrunk<NotificationDelegationMetadataEntity> response = new PageSearchTrunk<>();
        response.setResults(page.items());
        response.setLastEvaluatedKey(page.lastEvaluatedKey());
        log.debug("END search for one month");
        return response;
    }

    @Override
    public PageSearchTrunk<NotificationDelegationMetadataEntity> searchByIun(InputSearchNotificationDto inputSearchNotificationDto,
                                                                             String pk,
                                                                             String sk) {
        log.debug("START search notification delegation for single IUN - {} {}", pk, sk);

        GetItemEnhancedRequest request = GetItemEnhancedRequest.builder()
                .key(k -> k.partitionValue(pk).sortValue(sk).build())
                .build();

        log.debug("START query execution");
        NotificationDelegationMetadataEntity entity = table.getItem(request);

        // TODO capire se ha senso applicare i filtri

        PageSearchTrunk<NotificationDelegationMetadataEntity> result = new PageSearchTrunk<>();
        result.setResults(List.of(entity));
        log.debug("END query execution");
        return result;
    }

    private void addFilterExpression(InputSearchNotificationDelegatedDto searchDto,
                                     QueryEnhancedRequest.Builder requestBuilder) {
        Expression.Builder filterExpressionBuilder = Expression.builder();
        StringBuilder expressionBuilder = new StringBuilder();

        addStatusFilterExpression(searchDto.getStatuses(), filterExpressionBuilder, expressionBuilder);
        addSenderFilterExpression(searchDto.getSenderId(), filterExpressionBuilder, expressionBuilder);
        addReceiverFilterExpression(searchDto.getReceiverId(), filterExpressionBuilder, expressionBuilder);

        requestBuilder.filterExpression(filterExpressionBuilder
                .expression(expressionBuilder.length() > 0 ? expressionBuilder.toString() : null)
                .build());
    }

    private void addStatusFilterExpression(List<NotificationStatus> status,
                                           Expression.Builder filterExpressionBuilder,
                                           StringBuilder expressionBuilder) {
        if (CollectionUtils.isEmpty(status)) {
            log.debug("status filter is empty - skip add status filter expression");
            return;
        }
        addEventuallyAnd(expressionBuilder);
        expressionBuilder.append(NotificationDelegationMetadataEntity.FIELD_NOTIFICATION_STATUS).append(" IN (");
        for (int i = 0; i < status.size(); i++) {
            expressionBuilder.append(":ns").append(i);
            if (i < status.size() - 1) {
                expressionBuilder.append(", ");
            }
            filterExpressionBuilder.putExpressionValue(":ns" + i, AttributeValue.builder().s(status.get(i).toString()).build());
        }
        expressionBuilder.append(")) ");
    }

    private void addSenderFilterExpression(String senderId,
                                           Expression.Builder filterExpressionBuilder,
                                           StringBuilder expressionBuilder) {
        if (!StringUtils.hasText(senderId)) {
            log.debug("senderId is empty - skip add senderId filter expression");
            return;
        }
        addEqStringFilterExpression(senderId, NotificationDelegationMetadataEntity.FIELD_SENDER_ID, ":senderId", filterExpressionBuilder, expressionBuilder);
    }

    private void addReceiverFilterExpression(String receiverId,
                                             Expression.Builder filterExpressionBuilder,
                                             StringBuilder expressionBuilder) {
        if (!StringUtils.hasText(receiverId)) {
            log.debug("receiverId is empty - skip add receiverId filter expression");
            return;
        }
        addEqStringFilterExpression(receiverId, NotificationDelegationMetadataEntity.FIELD_RECIPIENT_ID, ":recipientId", filterExpressionBuilder, expressionBuilder);
    }

    private void addEqStringFilterExpression(String value, String fieldName, String filterName, Expression.Builder filterExpressionBuilder, StringBuilder expressionBuilder) {
        addEventuallyAnd(expressionBuilder);
        expressionBuilder.append(fieldName).append(" = ").append(filterName).append(")");
        filterExpressionBuilder.putExpressionValue(filterName, AttributeValue.builder().s(value).build());
    }

    private void addEventuallyAnd(StringBuilder expressionBuilder) {
        expressionBuilder.append(expressionBuilder.length() > 0 ? " AND (" : " (");
    }
}
