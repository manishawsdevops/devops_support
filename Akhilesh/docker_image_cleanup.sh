#!/bin/bash

# output = $(docker images -a --filter=dangling=true -q)

# if [[-n $output]]; then
#     echo "Non Empty String"
# else
#     echo "Empty String"
# fi

output1=$(docker images --format "{{.Repository}},{{.Tag}},{{.CreatedSince}}")

echo $output1
