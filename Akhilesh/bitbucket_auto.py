#Functionalities
#	1. It should lock/unlock/create the repository branch and remove the permissions to all changes for the users development, QA, implementation, devops.
#	2. For any request, the current branch would be development and the previous release branch which is locked would be production and also set the default branch.

import requests,base64

payload={}
headers = {
  'Authorization': 'Basic '
}

repository_id = 'akhilesh'
workspace_id = 'manishawsfreelancer'

# url = "https://api.bitbucket.org/2.0/repositories/" + workspace_id + '/' + repository_id

#list permissions - API /2.0/repositories/{workspace}/{repo_slug}/branch-restrictions

url = "https://api.bitbucket.org/2.0/repositories/" + workspace_id + '/' + repository_id + "/branch-restrictions"

response = requests.get(url, headers=headers, data=payload)



print(response.text)




