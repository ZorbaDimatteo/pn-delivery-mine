paId="pa_id"
paNotificationId=$1
#baseUrl='http://3.67.133.88:8080'
#baseUrl='https://h4hcl6trzf.execute-api.eu-central-1.amazonaws.com/beta/'
#apiKeySecret=zewBLfImEB3PEuwwwoxxk8DxJ1TkZxRD7ZpZ5qb4
#baseUrl='https://e9t7rm7qkg.execute-api.eu-central-1.amazonaws.com/beta/'
#baseUrl='http://18.194.140.160:8080'
baseUrl='http://127.0.0.1:8080'
apiKeySecret="hlNDsrXXfT9yRfoDv8w9FaVEul3AmonX73sVPHTB"
filePath=$2

mkdir ./tmp
echo -n "" > ./tmp/file.txt
echo -n '{
  "paNotificationId": "'${paNotificationId}'",
  "subject": "string",
  "cancelledIun": "string",
  "recipients": [
    {
      "fc": "string",
      "denomination": "string",
      "digitalDomicile": {
        "type": "PEC",
        "address": "string"
      },
      "physicalAddress": {
        "at": "string",
        "address": "string",
        "addressDetails": "string",
        "zip": "string",
        "municipality": "string",
        "province": "string"
      }
    }
  ],
  "documents": [
    {
      "digests": {
        "sha256": "loSha256DelFile"
      },
      "contentType": "application/pdf",
      "body": "' >> ./tmp/file.txt
base64 -i $filePath | sed 's/$/"/' >> ./tmp/file.txt
echo -n '
    }
  ],
  "payment": {
    "iuv": "string",
    "notificationFeePolicy": "FLAT_RATE",
    "f24": {
      "flatRate": {
        "digests": {
          "sha256": "string"
        },
        "contentType": "string",
        "body": "string"
      },
      "digital": {
        "digests": {
          "sha256": "string"
        },
        "contentType": "string",
        "body": "string"
      },
      "analog": {
        "digests": {
          "sha256": "string"
        },
        "contentType": "string",
        "body": "string"
      }
    }
  }
}' >> ./tmp/file.txt


curl -v -X 'POST' \
  "${baseUrl}/delivery/notifications/sent" \
  -H 'accept: */*' \
  -H "X-PagoPA-PN-PA: ${paId}" \
  -H 'Content-Type: application/json' \
  -H "x-api-key: ${apiKeySecret}" \
  --data-binary  "@tmp/file.txt"