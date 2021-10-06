from logging import log
import boto3
import sys

#This python script will read the loggroups names from the file and create those in 
# the aws cloud.

#Read the loggroups from text file and create the CW loggroup.

env = sys.argv[1]

client = boto3.client('logs')

try:
    with open('test_loggroups.txt',"r") as myfile:
        for i in myfile:
            appshort_name = i.rstrip('\n')
            loggroup_name = appshort_name + env + 'edl' + 'emr'
            if loggroup_name in client.describe_log_groups(logGroupNamePrefix=loggroup_name)['logGroups']:
                print(f"{loggroup_name} already exisisting")
            else:
                client.create_log_group(logGroupName=loggroup_name)
                print("loggroup_name created successfully")
except Exception as err:
    print(err)
