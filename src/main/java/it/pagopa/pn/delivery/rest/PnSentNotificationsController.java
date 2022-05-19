package it.pagopa.pn.delivery.rest;


import it.pagopa.pn.commons.exceptions.PnValidationException;
import it.pagopa.pn.commons_delivery.utils.EncodingUtils;
import it.pagopa.pn.delivery.PnDeliveryConfigs;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.SenderReadB2BApi;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.SenderReadWebApi;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.*;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.rest.dto.ResErrorDto;
import it.pagopa.pn.delivery.rest.utils.HandleValidation;
import it.pagopa.pn.delivery.svc.search.NotificationRetrieverService;
import it.pagopa.pn.delivery.utils.ModelMapperFactory;
import org.modelmapper.ModelMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Base64Utils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@RestController
public class PnSentNotificationsController implements SenderReadB2BApi,SenderReadWebApi {

    private final NotificationRetrieverService retrieveSvc;
    private final PnDeliveryConfigs cfg;
    private final ModelMapperFactory modelMapperFactory;
    public static final String VALIDATION_ERROR_STATUS = "Validation error";

    public PnSentNotificationsController(NotificationRetrieverService retrieveSvc, PnDeliveryConfigs cfg, ModelMapperFactory modelMapperFactory) {
        this.retrieveSvc = retrieveSvc;
        this.cfg = cfg;
        this.modelMapperFactory = modelMapperFactory;
    }

    @Override
    public ResponseEntity<FullSentNotification> getSentNotification(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, String iun) {
        InternalNotification internalNotification = retrieveSvc.getNotificationInformation( iun, true );
        ModelMapper mapper = modelMapperFactory.createModelMapper( InternalNotification.class, FullSentNotification.class );
        FullSentNotification result = mapper.map( internalNotification, FullSentNotification.class );
        return ResponseEntity.ok( result );
    }



    @Override
    public ResponseEntity<NotificationSearchResponse> searchSentNotification(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, Date startDate, Date endDate, String recipientId, NotificationStatus status, String subjectRegExp, String iunMatch, Integer size, String nextPagesKey) {
        InputSearchNotificationDto searchDto = new InputSearchNotificationDto.Builder()
                .bySender(true)
                .senderReceiverId(xPagopaPnCxId)
                .startDate(startDate.toInstant())
                .endDate(endDate.toInstant())
                .filterId(recipientId)
                .status(status)
                //.groups( groups != null ? Arrays.asList( groups ) : null )
                .subjectRegExp(subjectRegExp)
                .iunMatch(iunMatch)
                .size(size)
                .nextPagesKey(nextPagesKey)
                .build();

        ResultPaginationDto<NotificationSearchRow,String> serviceResult =  retrieveSvc.searchNotification( searchDto );

        ModelMapper mapper = modelMapperFactory.createModelMapper(ResultPaginationDto.class, NotificationSearchResponse.class );
        NotificationSearchResponse response = mapper.map( serviceResult, NotificationSearchResponse.class );
        return ResponseEntity.ok( response );
    }

    @ExceptionHandler({PnValidationException.class})
    public ResponseEntity<ResErrorDto> handleValidationException(PnValidationException ex){
        return HandleValidation.handleValidationException(ex, VALIDATION_ERROR_STATUS);
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return SenderReadB2BApi.super.getRequest();
    }

    @Override
    public ResponseEntity<NewNotificationRequestStatusResponse> getNotificationRequestStatus(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, String requestId, String paProtocolNumber, String idempotenceToken) {
        String iun = Base64Utils.encodeToString(requestId.getBytes(StandardCharsets.UTF_8));
        InternalNotification internalNotification = retrieveSvc.getNotificationInformation( iun, true );
        NewNotificationRequestStatusResponse response;
        if ( internalNotification.getNotificationStatus().equals(NotificationStatus.REFUSED)  || internalNotification.getNotificationStatus().equals(NotificationStatus.IN_VALIDATION)) {
            response = NewNotificationRequestStatusResponse.builder()
                    .errors(Collections.singletonList( ProblemError.builder()
                            .code( "Notification Refused or in Validation" )
                            .build() ))
                    .build();
        } else {
            ModelMapper mapper = modelMapperFactory.createModelMapper( InternalNotification.class, NewNotificationRequestStatusResponse.class );
            response = mapper.map( internalNotification, NewNotificationRequestStatusResponse.class );
        }
        return ResponseEntity.ok( response );
    }

    @Override
    public ResponseEntity<NotificationAttachmentDownloadMetadataResponse> getSentNotificationAttachment(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, String iun, BigDecimal recipientIdx, String attachmentName) {
        return SenderReadB2BApi.super.getSentNotificationAttachment(xPagopaPnUid, xPagopaPnCxType, xPagopaPnCxId, xPagopaPnCxGroups, iun, recipientIdx, attachmentName);
    }

    @Override
    public ResponseEntity<NotificationAttachmentDownloadMetadataResponse> getSentNotificationDocument(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, String iun, BigDecimal docIdx) {
        NotificationAttachmentDownloadMetadataResponse response = retrieveSvc.downloadDocumentWithRedirect(iun, docIdx.intValue());
        return ResponseEntity.ok( response );
    }
}
