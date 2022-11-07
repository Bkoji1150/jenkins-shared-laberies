#!/bin/sh

echo "Add files and do local commit"
git add -A
git commit -am "update timeout to 5m"

echo "add elasetic chance"
git push
