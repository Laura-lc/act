from pymongo import MongoClient
import json
import operator
import csv
import math
import sys
import os

if (len(sys.argv) < 4):
    print("Not enough arguments given.")
    sys.exit()

reachables_db = sys.argv[1]
base_dir = sys.argv[2]
db_name = sys.argv[3]

client = MongoClient('localhost', 27017)

db = client[db_name]

# Make the category page directory
categoryPath = os.path.join(base_dir, "Categories")

if not os.path.exists(categoryPath):
    os.makedirs(categoryPath)

def makePage(fileName, chemicals):
    with open(os.path.join(categoryPath, fileName), 'w') as target:
        for chemical in chemicals:
            if (chemical["inchikey"] is not None):
                chemName = chemical["inchikey"].encode("utf-8")
                chemLink = "[[{0}]]".format(chemName)
                target.write(chemLink)
                target.write("\n\n")

    print("Finished printing page name: " + fileName)

### generate Drug molecules pages
makePage("Drug_Molecules", db[reachables_db].find({"xref.DRUGBANK": {"$exists": True}}))

### generate sigma molecules pages
makePage("Sigma_Molecules", db[reachables_db].find({"xref.SIGMA": {"$exists": True}}))

### generate wikipedia molecules pages
makePage("Wikipedia_Molecules", db[reachables_db].find({"xref.WIKIPEDIA": {"$exists": True}}))

usageTerms = {"aroma": {}, "flavor": {}, "monomer": {}, "polymer": {}, "analgesic": {}}

### For each of the usage pages, we want to rank order the chemicals based on the proportion
### of links that are related to the usage term. If a chemical has a greater proportion of it's
### usage links linked to the usage term, it should list higher in the page. In order to do this,
### we create a dictionary for every usage term that maps the frequency of the usage term appearing
### to the chemical.

### First we find all the chemicals that have usage terms associated with them.
for chemical in db[reachables_db].find({"usage-wordcloud-filename": {"$ne": None}}):
    if (chemical["inchikey"]):
        chemName = chemical["inchikey"].encode("utf-8")
        chemLink = "[[{0}]]".format(chemName)

        dictOfUsageTerms = {}
        totalCount = 0

        for usage_term in chemical["xref"]["BING"]["metadata"]["usage_terms"]:
            dictOfUsageTerms[usage_term["usage_term"]] = len(usage_term["urls"])
            totalCount += len(usage_term["urls"])

        for key in dictOfUsageTerms.keys():
            for usageKey in usageTerms.keys():
                if usageKey in key:
                    freq = (dictOfUsageTerms[key] / totalCount) * 100
                    if freq in usageTerms[usageKey]:
                        usageTerms[usageKey][freq] += [chemLink]
                    else:
                        usageTerms[usageKey][freq] = [chemLink]

### We generate the usage terms pages that are sorted by the usage frequency
for term in usageTerms:
    fileName = term.capitalize()

    with open(os.path.join(categoryPath, fileName), 'w') as target:
        sortedKeys = sorted(usageTerms[term].keys(), reverse=True)
        for key in sortedKeys:
            for chemLink in usageTerms[term][key]:
                target.write(chemLink)
                target.write("\n\n")

    print("Finished printing page name: " + fileName)
