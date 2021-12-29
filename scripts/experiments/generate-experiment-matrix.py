import sys
import json

bms = [
# {"id":"ant", "class":"edu.berkeley.cs.jqf.examples.ant.ProjectBuilderTest", "method" : "testWithGenerator",
# "coveragePackages" : "org/apache/tools/ant/*"},
# {"id":"bcel", "class":"edu.berkeley.cs.jqf.examples.bcel.ParserTest", "method" : "testWithGenerator",
# "coveragePackages" : "org/apache/bcel/*"},
{"id":"closure", "class" : "edu.berkeley.cs.jqf.examples.closure.CompilerTest", "method": "testWithGenerator",
"coveragePackages" : "com/google/javascript/jscomp/*"},
# {"id":"maven", "class" : "edu.berkeley.cs.jqf.examples.maven.ModelReaderTest", "method" : "testWithGenerator",
# "coveragePackages" : "org/apache/maven/model/*"},
# {"id":"rhino", "class" : "edu.berkeley.cs.jqf.examples.rhino.CompilerTest", "method" : "testWithGenerator",
# "coveragePackages" : "org/mozilla/javascript/optimizer/*:org/mozilla/javascript/CodeGenerator*"}
]

memorySettings = [
{"JQF": "6g", "Central":"6g", "Knarr": "6g"},
{"JQF": "3g", "Central":"3g", "Knarr": "3g"},
{"JQF": "4g", "Central":"4g", "Knarr": "4g"},
{"JQF": "4g", "Central":"5g", "Knarr": "3g"},
]
configs=[]
for x in memorySettings:
    for bm in bms:
        tmp = bm.copy()
        tmp['Xmx'] = x
        configs.append(tmp)
print(json.dumps({'config':configs}))
