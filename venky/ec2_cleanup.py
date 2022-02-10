import boto3 

region = 'us-west-2'


client = boto3.client('resourcegroupstaggingapi', region_name= region)
client_ec2 = boto3.client('ec2',region_name = region)



def get_all_ec2_resources(client,region, service_type):
    response = client.get_resources(
    #PaginationToken='string',
    # TagFilters=[
    #     {
    #         'Key': 'string',
    #         'Values': [
    #             'string',
    #         ]
    #     },
    # ],
    #ResourcesPerPage=123,
    
    #TagsPerPage=123,
    ResourceTypeFilters=[service_type]
    #IncludeComplianceDetails=True|False,
    #ExcludeCompliantResources=True|False,
    #ResourceARNList=[
    #     'string',
    # ]
     )
    return response

def ec2_instance_cleanup(client, region, instance_id):
    response = client.describe_instance_status(
                # Filters=[
                #     {
                #         'Name': 'string',
                #         'Values': [
                #             'string',
                #         ]
                #     },
                # ],
                InstanceIds=[
                    instance_id,
                ],
                # MaxResults=123,
                # NextToken='string',
                # DryRun=True|False,
                # IncludeAllInstances=True|False
                )
    return response['InstanceStatuses'][0]['InstanceState']['Name']
    

ec2_instances = get_all_ec2_resources(client, region, 'ec2:instance')

try:
    for i in ec2_instances['ResourceTagMappingList']:
        instance_id = i['ResourceARN'].split('/')[1]
        print(f'**********Check wheteher {instance_id} needs a cleaup*********')
        instance_state = ec2_instance_cleanup(client_ec2,region, instance_id)
        if instance_state == 'running':
            print(f'{instance_id} doesnot need a cleanup')
        else:
            print(f'Instance state of {instance_id} is {instance_state} and can be cleaned ')
        print('****************************************************************')
except Exception as err:
    print(f'Error cleaning up {instance_id}, {err}')
    print('****************************************************************')




# ec2_volumes = get_all_ec2_resources(client, region, 'ec2:volume')
# print(ec2_instances)
# print(ec2_volumes)
