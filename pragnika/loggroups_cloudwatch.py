from logging import log
import boto3

client = boto3.client('logs')

destination_arn = '<arn_tobe_pasted>'
logname_prefix = '<logname_prefix>'

logGroups = (client.describe_log_groups(
    logGroupNamePrefix=logname_prefix))['logGroups']

for i in logGroups:
    print(i['logGroupName'])
    client.put_subscription_filter(
        logGroupName=i['logGroupName'],
        filterName='send-all',
        filterPattern='',
        destinationArn=destination_arn
    )
