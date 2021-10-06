from logging import log
import boto3

#This python script will read the loggroups names from the file and create those in 
# the aws cloud.

#Read the loggroups from text file and create the CW loggroup.

client = boto3.client('logs')

try:
    with open('test_loggroups.txt',"r") as myfile:
        for i in myfile:
            loggroup_name = i.rstrip('\n')
            client.create_log_group(
                logGroupName=loggroup_name
            )
            print("loggroup_name created successfully")
except Exception as err:
    print(err)
