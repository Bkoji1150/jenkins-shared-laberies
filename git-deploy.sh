#!/bin/sh

echo "Add files and do local commit"
git add -A
git commit -am "update the cipipipeline for groovy"

echo "add elasetic chance"
git push
