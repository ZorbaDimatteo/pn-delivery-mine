package it.pagopa.pn.delivery.svc;

import it.pagopa.pn.delivery.exception.PnNotFoundException;
import it.pagopa.pn.delivery.exception.PnNotificationNotFoundException;
import it.pagopa.pn.delivery.generated.openapi.clients.deliverypush.model.NotificationFeePolicy;
import it.pagopa.pn.delivery.generated.openapi.clients.deliverypush.model.NotificationProcessCostResponse;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationCostResponse;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationPriceResponse;
import it.pagopa.pn.delivery.middleware.AsseverationEventsProducer;
import it.pagopa.pn.delivery.middleware.NotificationDao;
import it.pagopa.pn.delivery.middleware.notificationdao.NotificationCostEntityDao;
import it.pagopa.pn.delivery.middleware.notificationdao.NotificationMetadataEntityDao;
import it.pagopa.pn.delivery.middleware.notificationdao.entities.NotificationMetadataEntity;
import it.pagopa.pn.delivery.models.AsseverationEvent;
import it.pagopa.pn.delivery.models.InternalAsseverationEvent;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.models.InternalNotificationCost;
import it.pagopa.pn.delivery.pnclient.deliverypush.PnDeliveryPushClientImpl;
import it.pagopa.pn.delivery.utils.RefinementLocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import static it.pagopa.pn.delivery.exception.PnDeliveryExceptionCodes.ERROR_CODE_DELIVERY_NOTIFICATIONCOSTNOTFOUND;
import static it.pagopa.pn.delivery.exception.PnDeliveryExceptionCodes.ERROR_CODE_DELIVERY_NOTIFICATIONMETADATANOTFOUND;

@Service
@Slf4j
public class NotificationPriceService {
    private final Clock clock;
    private final NotificationCostEntityDao notificationCostEntityDao;
    private final NotificationDao notificationDao;
    private final NotificationMetadataEntityDao notificationMetadataEntityDao;
    private final PnDeliveryPushClientImpl deliveryPushClient;

    private final AsseverationEventsProducer asseverationEventsProducer;
    private final RefinementLocalDate refinementLocalDateUtils;

    public NotificationPriceService(Clock clock, NotificationCostEntityDao notificationCostEntityDao, NotificationDao notificationDao, NotificationMetadataEntityDao notificationMetadataEntityDao, PnDeliveryPushClientImpl deliveryPushClient, AsseverationEventsProducer asseverationEventsProducer, RefinementLocalDate refinementLocalDateUtils) {
        this.clock = clock;
        this.notificationCostEntityDao = notificationCostEntityDao;
        this.notificationDao = notificationDao;
        this.notificationMetadataEntityDao = notificationMetadataEntityDao;
        this.deliveryPushClient = deliveryPushClient;
        this.asseverationEventsProducer = asseverationEventsProducer;
        this.refinementLocalDateUtils = refinementLocalDateUtils;
    }

    public NotificationPriceResponse getNotificationPrice(String paTaxId, String noticeCode) {
        log.info( "Get notification price for paTaxId={} noticeCode={}", paTaxId, noticeCode );
        InternalNotificationCost internalNotificationCost = getInternalNotificationCost(paTaxId, noticeCode);
        String iun = internalNotificationCost.getIun();
        log.info( "Get notification with iun={}", iun);
        InternalNotification internalNotification = getInternalNotification(iun);
        NotificationFeePolicy notificationFeePolicy = NotificationFeePolicy.fromValue(
                internalNotification.getNotificationFeePolicy().getValue()
        );
        int recipientIdx = internalNotificationCost.getRecipientIdx();
        // se la lista degli id non presente nell'internal notifications la posso recuperare dalla notificationMetadataEntity
        String recipientId = internalNotification.getRecipientIds().get(recipientIdx);
        log.info( "Get notification process cost with iun={} recipientId={} recipientIdx={} feePolicy={}", iun, recipientId, recipientIdx, notificationFeePolicy);
        NotificationProcessCostResponse notificationProcessCost = getNotificationProcessCost(iun, recipientId, recipientIdx, notificationFeePolicy, internalNotification.getSentAt());

        // invio l'evento di asseverazione sulla coda
        log.info( "Send asseveration event iun={} creditorTaxId={} noticeCode={}", iun, paTaxId, noticeCode );
        asseverationEventsProducer.sendAsseverationEvent(
                createInternalAsseverationEvent(internalNotificationCost, internalNotification)
        );

        // creazione dto response
        return NotificationPriceResponse.builder()
                .amount( notificationProcessCost.getAmount() )
                .refinementDate( refinementLocalDateUtils.setLocalRefinementDate( notificationProcessCost.getRefinementDate() ) )
                .notificationViewDate( refinementLocalDateUtils.setLocalRefinementDate( notificationProcessCost.getNotificationViewDate() ) )
                .iun( iun )
                .build();
    }

    private InternalAsseverationEvent createInternalAsseverationEvent(InternalNotificationCost internalNotificationCost, InternalNotification internalNotification) {
        Instant now = clock.instant();
        String formattedNow = refinementLocalDateUtils.formatInstantToString(now);
        String formattedSentAt = refinementLocalDateUtils.formatInstantToString(internalNotification.getSentAt().toInstant());
        return InternalAsseverationEvent.builder()
                .iun(internalNotificationCost.getIun())
                .notificationSentAt(formattedSentAt)
                .creditorTaxId(internalNotificationCost.getCreditorTaxIdNoticeCode().split("##")[0])
                .noticeCode(internalNotificationCost.getCreditorTaxIdNoticeCode().split("##")[1])
                .senderPaId(internalNotification.getSenderPaId())
                .recipientIdx(internalNotificationCost.getRecipientIdx())
                .debtorPosUpdateDate(formattedNow)
                .recordCreationDate(formattedNow)
                .version(1)
                .moreFields( AsseverationEvent.Payload.AsseverationMoreField.builder().build() )
                .build();
    }

    private InternalNotification getInternalNotification(String iun) {
        Optional<InternalNotification> optionalNotification = notificationDao.getNotificationByIun(iun);
        if (optionalNotification.isPresent()) {
            return optionalNotification.get();
        } else {
            log.error( "Unable to find notification for iun={}", iun);
            throw new PnNotificationNotFoundException( String.format("Unable to find notification for iun=%s", iun));
        }
    }

    private NotificationProcessCostResponse getNotificationProcessCost(String iun, String recipientId, int recipientIdx, NotificationFeePolicy notificationFeePolicy, OffsetDateTime sentAt) {
        // controllo che notifica sia stata accettata cercandola nella tabella notificationMetadata tramite PK iun##recipientId
        getNotificationMetadataEntity(iun, recipientId, sentAt);

        // contatto delivery-push per farmi calcolare tramite iun, recipientIdx, notificationFeePolicy costo della notifica
        // delivery-push mi risponde con amount, data perfezionamento presa visione, data perfezionamento decorrenza termini
        return deliveryPushClient.getNotificationProcessCost(iun, recipientIdx, notificationFeePolicy);
    }

    private void getNotificationMetadataEntity(String iun, String recipientId, OffsetDateTime sentAt) {
        Optional<NotificationMetadataEntity> optionalNotificationMetadataEntity = notificationMetadataEntityDao.get(Key.builder()
                .partitionValue(iun + "##" + recipientId)
                .sortValue( sentAt.toString() )
                .build()
        );
        if (optionalNotificationMetadataEntity.isEmpty()) {
            log.info( "Notification iun={}, recipientId={} not found in NotificationsMetadata", iun, recipientId);
            throw new PnNotFoundException("Notification metadata not found", String.format(
                    "Notification iun=%s, recipientId=%s not found in NotificationsMetadata", iun, recipientId),
                    ERROR_CODE_DELIVERY_NOTIFICATIONMETADATANOTFOUND );
        }
    }

    // NOTA da eliminare poiché non utilizzato da pn-delivery-push
    public NotificationCostResponse getNotificationCost(String paTaxId, String noticeCode) {
        String iun;
        int recipientIdx;
        log.info( "Get notification cost info for paTaxId={} noticeCode={}", paTaxId, noticeCode );
        InternalNotificationCost internalNotificationCost = getInternalNotificationCost(paTaxId, noticeCode);
        iun = internalNotificationCost.getIun();
        recipientIdx = internalNotificationCost.getRecipientIdx();

        return NotificationCostResponse.builder()
                .iun( iun )
                .recipientIdx( recipientIdx )
                .build();
    }

    private InternalNotificationCost getInternalNotificationCost( String paTaxId, String noticeCode ) {
        Optional<InternalNotificationCost> optionalNotificationCost = notificationCostEntityDao.getNotificationByPaymentInfo( paTaxId, noticeCode );
        if (optionalNotificationCost.isPresent()) {
            return optionalNotificationCost.get();
        } else {
            log.info( "No notification cost info by paTaxId={} noticeCode={}", paTaxId, noticeCode );
            throw new PnNotFoundException("Notification cost not found", String.format( "No notification cost info by paTaxId=%s noticeCode=%s", paTaxId, noticeCode ) , ERROR_CODE_DELIVERY_NOTIFICATIONCOSTNOTFOUND );
        }
    }
}
