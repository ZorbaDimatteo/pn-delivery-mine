package it.pagopa.pn.delivery.middleware.notificationdao;




import it.pagopa.pn.commons.abstractions.IdConflictException;
import it.pagopa.pn.commons.abstractions.impl.MiddlewareTypes;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationSearchRow;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.NotificationStatus;
import it.pagopa.pn.delivery.middleware.notificationdao.entities.NotificationMetadataEntity;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.svc.search.PnLastEvaluatedKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.w3c.dom.Attr;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.*;

@ExtendWith(SpringExtension.class)
@TestPropertySource(properties = {
        NotificationMetadataEntityDao.IMPLEMENTATION_TYPE_PROPERTY_NAME + "=" + MiddlewareTypes.DYNAMO,
        "aws.region-code=us-east-1",
        "aws.profile-name=${PN_AWS_PROFILE_NAME:default}",
        "aws.endpoint-url=http://localhost:4566",
        "pn.delivery.notification-dao.table-name=Notifications",
        "pn.delivery.notification-metadata-dao.table-name=NotificationsMetadata"
})
@SpringBootTest
class NotificationMetadataEntityDaoDynamoTestIT {

    @Autowired
    private NotificationMetadataEntityDao notificationMetadataEntityDao;

    @Test
    void searchNotificationMetadataBySender() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( true )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "c_b429" )
                .size( 10 )
                .nextPagesKey( null )
                .build();
        String indexName = "senderId";
        String partitionValue = "c_b429##202204";

        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                null
        );

        //Then
        Assertions.assertNotNull( result );

        /*List<ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey>> resultList = new ArrayList<>();
        PnLastEvaluatedKey lastEvaluatedKey = null;
        do {
            ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result =  notificationMetadataEntityDao.searchNotificationMetadata( inputSearch, lastEvaluatedKey );
            if (!result.getNextPagesKey().isEmpty() ) {
                lastEvaluatedKey = result.getNextPagesKey().get( 0 );
            } else {
                lastEvaluatedKey = null;
            }
            resultList.add( result );
        } while (lastEvaluatedKey !=null);*/
        /*Map<String, AttributeValue> internalLastEvaluatedKey = new HashMap<>();
        internalLastEvaluatedKey.put( "iun_recipientId", AttributeValue.builder().s( "0020##PF003" ).build() );
        internalLastEvaluatedKey.put( "sentAt", AttributeValue.builder().s( "2022-03-20T20:20:20Z" ).build() );
        internalLastEvaluatedKey.put( "senderId_creationMonth", AttributeValue.builder().s( "MI##202203" ).build() );

        PnLastEvaluatedKey pnLastEvaluatedKey = new PnLastEvaluatedKey();
        pnLastEvaluatedKey.setExternalLastEvaluatedKey( "MI##202203" );
        pnLastEvaluatedKey.setInternalLastEvaluatedKey( internalLastEvaluatedKey );*/
    }

    @Test
    void searchNotificationMetadataNextPageBySender() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( true )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "c_b429" )
                .size( 10 )
                .nextPagesKey( "eyJlayI6ImNfYjQyOSMjMjAyMjA0IiwiaWsiOnsiaXVuX3JlY2lwaWVudElkIjoiY19iNDI5LTIwMjIwNDA0MTYwNCMjZWQ4NGI4YzktNDQ0ZS00MTBkLTgwZDctY2ZhZDZhYTEyMDcwIiwic2VudEF0IjoiMjAyMi0wNC0wNFQxNDowNDowNy41MjA1NThaIiwic2VuZGVySWRfY3JlYXRpb25Nb250aCI6ImNfYjQyOSMjMjAyMjA0In19" )
                .build();

        String indexName = "senderId";
        String partitionValue = "c_b429##202204";

        PnLastEvaluatedKey lek = new PnLastEvaluatedKey();
        lek.setExternalLastEvaluatedKey( "c_b429##202204" );
        lek.setInternalLastEvaluatedKey( Map.ofEntries(
                        Map.entry( "iun_recipientId", AttributeValue.builder()
                                .s( "c_b429-202204041604##ed84b8c9-444e-410d-80d7-cfad6aa12070" )
                                .build() ),
                        Map.entry( "sentAt", AttributeValue.builder().s("2022-04-04T14:04:07.520558Z")
                                .build() ),
                        Map.entry( "senderId_creationMonth", AttributeValue.builder().s("c_b429##202204")
                                .build() )
                        )
        );

        //When
        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                lek
        );

        //Then
        Assertions.assertNotNull( result );
    }

    @Test
    void searchNotificationMetadataByRecipient() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( false )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "ed84b8c9-444e-410d-80d7-cfad6aa12070" )
                .size( 10 )
                .nextPagesKey( null )
                .build();

        String indexName = "recipientId";
        String partitionValue = "ed84b8c9-444e-410d-80d7-cfad6aa12070##202204";

        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                null
        );

        Assertions.assertNotNull( result );
    }

    @Test
    void searchNotificationMetadataNextPageByRecipient() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( false )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "ed84b8c9-444e-410d-80d7-cfad6aa12070" )
                .size( 10 )
                .nextPagesKey( "eyJlayI6ImVkODRiOGM5LTQ0NGUtNDEwZC04MGQ3LWNmYWQ2YWExMjA3MCMjMjAyMjA0IiwiaWsiOnsiaXVuX3JlY2lwaWVudElkIjoiY19iNDI5LTIwMjIwNDA0MTYwNCMjZWQ4NGI4YzktNDQ0ZS00MTBkLTgwZDctY2ZhZDZhYTEyMDcwIiwicmVjaXBpZW50SWRfY3JlYXRpb25Nb250aCI6ImVkODRiOGM5LTQ0NGUtNDEwZC04MGQ3LWNmYWQ2YWExMjA3MCMjMjAyMjA0Iiwic2VudEF0IjoiMjAyMi0wNC0wNFQxNDowNDowNy41MjA1NThaIn19" )
                .build();

        String indexName = "recipientId";
        String partitionValue = "ed84b8c9-444e-410d-80d7-cfad6aa12070##202204";

        PnLastEvaluatedKey lek = new PnLastEvaluatedKey();
        lek.setExternalLastEvaluatedKey( "ed84b8c9-444e-410d-80d7-cfad6aa12070##202204" );
        lek.setInternalLastEvaluatedKey( Map.ofEntries(
                        Map.entry( "iun_recipientId", AttributeValue.builder()
                                .s( "c_b429-202204041604##ed84b8c9-444e-410d-80d7-cfad6aa12070" )
                                .build() ),
                        Map.entry( "sentAt", AttributeValue.builder().s("2022-04-04T14:04:07.520558Z")
                                .build() ),
                        Map.entry( "recipientId_creationMonth", AttributeValue.builder().s("ed84b8c9-444e-410d-80d7-cfad6aa12070##202204")
                                .build() )
                )
        );

        //When
        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                lek
        );

        //Then
        Assertions.assertNotNull( result );

    }

    @Test
    void searchNotificationMetadataWithRecipientFilter() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( true )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "c_b429" )
                .size( 10 )
                .filterId( "ed84b8c9-444e-410d-80d7-cfad6aa12070" )
                .build();

        String indexName = "senderId_recipientId";
        String partitionValue = "c_b429##ed84b8c9-444e-410d-80d7-cfad6aa12070";

        //When
        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                null
        );

        //Then
        Assertions.assertNotNull( result );
    }

    @Test
    void searchNotificationMetadataWithNextPageRecipientFilter() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( true )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "c_b429" )
                .size( 10 )
                .nextPagesKey( "eyJlayI6ImNfYjQyOSMjZWQ4NGI4YzktNDQ0ZS00MTBkLTgwZDctY2ZhZDZhYTEyMDcwIiwiaWsiOnsiaXVuX3JlY2lwaWVudElkIjoiY19iNDI5LTIwMjIwNDA1MTEyOCMjZWQ4NGI4YzktNDQ0ZS00MTBkLTgwZDctY2ZhZDZhYTEyMDcwIiwic2VudEF0IjoiMjAyMi0wNC0wNVQwOToyODo0Mi4zNTgxMzZaIiwic2VuZGVySWRfcmVjaXBpZW50SWQiOiJjX2I0MjkjI2VkODRiOGM5LTQ0NGUtNDEwZC04MGQ3LWNmYWQ2YWExMjA3MCJ9fQ==" )
                .filterId( "ed84b8c9-444e-410d-80d7-cfad6aa12070" )
                .build();

        String indexName = "senderId_recipientId";
        String partitionValue = "c_b429##ed84b8c9-444e-410d-80d7-cfad6aa12070";

        PnLastEvaluatedKey lek = new PnLastEvaluatedKey();
        lek.setExternalLastEvaluatedKey( "ed84b8c9-444e-410d-80d7-cfad6aa12070##202204" );
        lek.setInternalLastEvaluatedKey( Map.ofEntries(
                        Map.entry( "iun_recipientId", AttributeValue.builder()
                                .s( "c_b429-202204041604##ed84b8c9-444e-410d-80d7-cfad6aa12070" )
                                .build() ),
                        Map.entry( "sentAt", AttributeValue.builder().s("2022-04-04T14:04:07.520558Z")
                                .build() ),
                        Map.entry( "senderId_recipientId", AttributeValue.builder().s("c_b429##ed84b8c9-444e-410d-80d7-cfad6aa12070")
                                .build() )
                )
        );

        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                lek
        );

        Assertions.assertNotNull( result );
    }

    @Test
    void searchNotificationMetadataWithStatusFilter() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( true )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "c_b492" )
                .size( 10 )
                .status( NotificationStatus.VIEWED )
                .build();

        String indexName = "senderId";
        String partitionValue = "c_b492##202204";

        ResultPaginationDto<NotificationSearchRow,PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                null
        );

        Assertions.assertNotNull( result );

    }

    @Test
    void searchNotificationMetadataWithIunFilter() {
        //Given
        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( true )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "c_b492" )
                .size( 10 )
                .iunMatch( "c_b429-202204041543" )
                .build();

        String indexName = "senderId";
        String partitionValue = "c_b492##202204";

        ResultPaginationDto<NotificationSearchRow,PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                null
        );

        Assertions.assertNotNull( result );
    }

    @Test
    void searchNotificationMetadataWithGroupsFilter() {
        //Given
        String[] groups = {"Group1"};


        InputSearchNotificationDto inputSearch = new InputSearchNotificationDto.Builder()
                .bySender( true )
                .startDate( Instant.parse( "2022-03-01T00:00:00.00Z" ) )
                .endDate( Instant.parse( "2022-04-30T00:00:00.00Z" ) )
                .senderReceiverId( "c_b492" )
                .size( 10 )
                .groups( Arrays.asList( groups )  )
                .build();

        String indexName = "senderId";
        String partitionValue = "c_b429##202204";

        ResultPaginationDto<NotificationSearchRow, PnLastEvaluatedKey> result = notificationMetadataEntityDao.searchForOneMonth(
                inputSearch,
                indexName,
                partitionValue,
                inputSearch.getSize(),
                null
        );

        Assertions.assertNotNull( result );
    }

    @Test
    void putIfAbsent() throws IdConflictException {
        //Given
        NotificationMetadataEntity metadataEntityToInsert = newNotificationMetadata();

        Key key = Key.builder()
                .partitionValue(metadataEntityToInsert.getIun_recipientId())
                .sortValue( metadataEntityToInsert.getSentAt().toString() )
                .build();

        //When
        notificationMetadataEntityDao.putIfAbsent( metadataEntityToInsert );

        //Then
        Optional<NotificationMetadataEntity> elementFromDb = notificationMetadataEntityDao.get( key );

        Assertions.assertTrue( elementFromDb.isPresent() );
        Assertions.assertEquals( metadataEntityToInsert, elementFromDb.get() );
    }

    private NotificationMetadataEntity newNotificationMetadata() {
        Map<String,String> tableRowMap = new HashMap<>();
        tableRowMap.put( "iun", "IUN" );
        tableRowMap.put( "recipientsIds", "[recipientId]" );
        tableRowMap.put( "subject", "Notifica IUN" );
        return NotificationMetadataEntity.builder()
                .iun_recipientId( "IUN##RecipientId" )
                .notificationGroup( "NotificationGroup1" )
                .notificationStatus( NotificationStatus.ACCEPTED.toString() )
                .recipientIds( Collections.singletonList("RecipientId") )
                .recipientOne( true )
                .senderId( "SenderId" )
                .recipientId_creationMonth( "RecipientId##202203" )
                .senderId_creationMonth("SenderId##202203")
                .senderId_recipientId( "SenderId##RecipientId" )
                .sentAt( Instant.parse( "2022-03-17T17:51:00.00Z" ) )
                .tableRow( tableRowMap )
                .build();
    }
}