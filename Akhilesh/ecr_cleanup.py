import boto3

client = boto3.client('ecr')

response = client.describe_repositories(
    registryId='string',
    repositoryNames=[
        'string',
    ],
    nextToken='string',
    maxResults=1000
)
print(response)

