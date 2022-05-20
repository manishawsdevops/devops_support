import boto3
import json
secrets_client = boto3.client('secretsmanager')
secret_id = ''
response = client.tag_resource(
    SecretId=secret_id,
    Tags=[
        {
            'Key': 'Test_Update_Tags',
            'Value': 'Testing_Successfull'
        },
    ]
)
