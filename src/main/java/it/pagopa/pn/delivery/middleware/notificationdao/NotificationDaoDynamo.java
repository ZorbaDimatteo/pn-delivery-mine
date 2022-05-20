package it.pagopa.pn.delivery.middleware.notificationdao;



import it.pagopa.pn.commons.abstractions.IdConflictException;
import it.pagopa.pn.commons.abstractions.impl.MiddlewareTypes;
import it.pagopa.pn.delivery.generated.openapi.clients.datavault.model.*;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationDigitalAddress;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationPhysicalAddress;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationRecipient;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationSearchRow;
import it.pagopa.pn.delivery.middleware.NotificationDao;
import it.pagopa.pn.delivery.middleware.notificationdao.entities.NotificationEntity;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.pnclient.datavault.PnDataVaultClientImpl;
import it.pagopa.pn.delivery.svc.search.PnLastEvaluatedKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class NotificationDaoDynamo implements NotificationDao {

    private final NotificationEntityDao entityDao;
    private final NotificationMetadataEntityDao metadataEntityDao;
    private final DtoToEntityNotificationMapper dto2entityMapper;
    private final EntityToDtoNotificationMapper entity2DtoMapper;
    private final PnDataVaultClientImpl pnDataVaultClient;

    public NotificationDaoDynamo(
            NotificationEntityDao entityDao,
            NotificationMetadataEntityDao metadataEntityDao, DtoToEntityNotificationMapper dto2entityMapper,
            EntityToDtoNotificationMapper entity2DtoMapper, PnDataVaultClientImpl pnDataVaultClient) {
        this.entityDao = entityDao;
        this.metadataEntityDao = metadataEntityDao;
        this.dto2entityMapper = dto2entityMapper;
        this.entity2DtoMapper = entity2DtoMapper;
        this.pnDataVaultClient = pnDataVaultClient;
    }

    @Override
    public void addNotification(InternalNotification internalNotification) throws IdConflictException {

        List<NotificationRecipientAddressesDto> recipientAddressesDtoList = new ArrayList<>();
        List<NotificationRecipient> cleanedRecipientList = new ArrayList<>();
        for ( NotificationRecipient recipient  : internalNotification.getRecipients()) {
            String opaqueTaxId = pnDataVaultClient.ensureRecipientByExternalId( RecipientType.fromValue( recipient.getRecipientType().getValue() ), recipient.getTaxId() );
            recipient.setTaxId( opaqueTaxId );
            NotificationRecipientAddressesDto recipientAddressesDto = new NotificationRecipientAddressesDto()
                    .denomination( recipient.getDenomination() )
                    .digitalAddress( createDigitalDomicile( recipient.getDigitalDomicile() ) )
                    .physicalAddress( createAnalogDomicile( recipient.getPhysicalAddress() ) );
            recipientAddressesDtoList.add( recipientAddressesDto );
            cleanedRecipientList.add( removeConfidantialInfo( recipient ) );
        }

        pnDataVaultClient.updateNotificationAddressesByIun( internalNotification.getIun(), recipientAddressesDtoList );
        internalNotification.setRecipients( cleanedRecipientList );

        NotificationEntity entity = dto2entityMapper.dto2Entity( internalNotification );
        entityDao.putIfAbsent( entity );
    }

    private NotificationRecipient removeConfidantialInfo(NotificationRecipient recipient) {
        return NotificationRecipient.builder()
                .recipientType( recipient.getRecipientType() )
                .taxId( recipient.getTaxId() )
                .payment( recipient.getPayment() )
                .build();
    }

    private AnalogDomicile createAnalogDomicile(NotificationPhysicalAddress notificationPhysicalAddress ) {
        return new AnalogDomicile()
                .address( notificationPhysicalAddress.getAddress() )
                .addressDetails( notificationPhysicalAddress.getAddressDetails() )
                .at( notificationPhysicalAddress.getAt() )
                .cap( notificationPhysicalAddress.getZip() )
                .municipality( notificationPhysicalAddress.getMunicipality() )
                .province( notificationPhysicalAddress.getProvince() )
                .state( notificationPhysicalAddress.getForeignState() );
    }

    private AddressDto createDigitalDomicile(NotificationDigitalAddress digitalAddress) {
        return new AddressDto()
                .value( digitalAddress.getAddress() );
    }

    @Override
    public Optional<InternalNotification> getNotificationByIun(String iun) {
        Key keyToSearch = Key.builder()
                .partitionValue(iun)
                .build();
        Optional<InternalNotification> daoResult = entityDao.get( keyToSearch )
                .map( entity2DtoMapper::entity2Dto );

        if(daoResult.isPresent()) {
            List<NotificationRecipient> daoNotificationRecipientList = daoResult.get().getRecipients();
            List<BaseRecipientDto> baseRecipientDtoList = pnDataVaultClient.getRecipientDenominationByInternalId( daoNotificationRecipientList.stream()
                    .map(NotificationRecipient::getTaxId)
                    .collect(Collectors.toList())
            );

            List<NotificationRecipientAddressesDto> notificationRecipientAddressesDtoList = pnDataVaultClient.getNotificationAddressesByIun( daoResult.get().getIun() );

            for ( NotificationRecipient recipient : daoNotificationRecipientList ) {
                String opaqueTaxId = recipient.getTaxId();
                Optional<BaseRecipientDto> optionalMatchinBaseRec =  baseRecipientDtoList.stream()
                        .filter( baseRec ->
                                opaqueTaxId.equals(baseRec.getInternalId()) ).findFirst();
                if (optionalMatchinBaseRec.isPresent()) {
                    recipient.setTaxId( optionalMatchinBaseRec.get().getTaxId() );
                    recipient.setDenomination( optionalMatchinBaseRec.get().getDenomination() );

                    Optional<NotificationRecipientAddressesDto> optionalMatchingRecipientAddress = notificationRecipientAddressesDtoList.stream()
                            .filter( recipientAddress ->
                                    recipient.getDenomination().equals( recipientAddress.getDenomination() ) ).findFirst();
                    if (optionalMatchingRecipientAddress.isPresent()) {
                        recipient.setDigitalDomicile( setNotificationDigitalAddress( optionalMatchingRecipientAddress.get().getDigitalAddress() ));
                        recipient.setPhysicalAddress( setNotificationPhysicalAddress( optionalMatchingRecipientAddress.get().getPhysicalAddress() ) );
                    } else {
                        log.error( "Unable to find any recipient addresses from data-vault for recipient={}", opaqueTaxId );
                    }

                } else {
                    log.error( "Unable to find any recipient info from data-vault for recipient={}", opaqueTaxId );
                }
            }
        }
        return daoResult;
    }

    private NotificationDigitalAddress setNotificationDigitalAddress( AddressDto addressDto ) {
        return NotificationDigitalAddress.builder()
                .type( NotificationDigitalAddress.TypeEnum.PEC )
                .address( addressDto.getValue() )
                .build();
    }

    private NotificationPhysicalAddress setNotificationPhysicalAddress( AnalogDomicile analogDomicile ) {
        return NotificationPhysicalAddress.builder()
                .foreignState( analogDomicile.getState() )
                .address( analogDomicile.getAddress() )
                .addressDetails( analogDomicile.getAddressDetails() )
                .at( analogDomicile.getAt() )
                .zip( analogDomicile.getCap() )
                .province( analogDomicile.getProvince() )
                .municipality( analogDomicile.getMunicipality() )
                .build();
    }

    @Override
    public ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> searchForOneMonth(InputSearchNotificationDto inputSearchNotificationDto, String indexName, String partitionValue, int size, PnLastEvaluatedKey lastEvaluatedKey) {
        return this.metadataEntityDao.searchForOneMonth( inputSearchNotificationDto, indexName, partitionValue, size, lastEvaluatedKey );
    }


    Predicate<String> buildRegexpPredicate(String subjectRegExp) {
        Predicate<String> matchSubject;
        if (subjectRegExp != null) {
            matchSubject = Objects::nonNull;
            matchSubject = matchSubject.and(Pattern.compile("^" + subjectRegExp + "$").asMatchPredicate());
        } else {
            matchSubject = x -> true;
        }
        return matchSubject;
    }
}
