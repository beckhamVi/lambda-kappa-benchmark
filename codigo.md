#lampda
.\build\Debug\simulator.exe --brokers localhost:9092 --topic events --total 10000 --rate 2000


#kappa -- csv
##.\build\Debug\simulator.exe --brokers localhost:9092 --topic events-kappa-bench --replay-csv ../data/sample.csv --rate 0
.\build\Debug\simulator.exe --brokers localhost:9092 --topic events-kappa --replay-csv ../data/sample.csv --rate 0

#kappa - stream
##.\build\Debug\simulator.exe --brokers localhost:9092 --topic events-kappa-bench --total 10000 --rate 200
.\build\Debug\simulator.exe --brokers localhost:9092 --topic events-kappa --total 10000 --rate 2000

#reset data of kappa
.\reset-env.ps1 kappa

#reset data of lampda
.\reset-env.ps1 lambda

#reset all
.\reset-env.ps1 all