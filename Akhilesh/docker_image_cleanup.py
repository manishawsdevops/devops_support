# 1. It should delete only the build images, not every image.
# 2. Images older than a week should be deleted except 
# ^node|^mariadb|pmt-gra|^kapacitor|^debian|^pmt-centos|^centos|ops-dash|^matisq|^atmoz|^oraclelinux|
# ^openjdk|^fluent| ^tiangolo|^chronograf|ops-dash|^portainer|^dashboardjenkins|^dougbtv|^woosley|
# ^influx|^quay|^dkron|^nginx|^registry.paymentus.io\/sit\/(prod|uat)'
#  |tr -s ’ ' |cut -d ’ ' -f1,2 |sed ‘s/ /:/g’) ||true
# 3. All the build images should be deleted except the current release. 
# The current release changes every month, so the script should know the current release.

import docker
import os
from datetime import datetime

client = docker.from_env()

images_to_preserve = [
    'node','mariadb','pmt-gra','kapacitor','debian','pmt-centos','centos','ops-dash','matisq',
    'atmoz','oraclelinux','openjdk','fluent','tiangolo','chronograf','ops-dash','portainer','dashboardjenkins'
    'dougbtv','woosley','influx','quay','dkron','nginx','registry.paymentus.io/sit/prod','registry.paymentus.io/sit/prod']

for i in client.images.list():
    delete_flag = False
    d1 = datetime.now().date()
    d2 = datetime.strptime(i.attrs['Created'][0:10], '%Y-%m-%d').date()
    delta = d1 - d2
    if int(delta.days) > 5:
        docker_image = i.tags[0].split(':')[0]
        for img in images_to_preserve:
            if img in docker_image:
                delete_flag = True              
        if not delete_flag:
            k = i.id.split(':')[1]
            os.system(f'docker rmi {k}')
            print(f'Deleted Docker image {docker_image}')
    
    
    
