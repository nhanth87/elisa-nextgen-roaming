# artifacts/ — build inputs (NOT committed to upstream)

Produced from the Nextgen-DRA repository:

    dist-tools/package-dist.sh                       -> dra-dist.tar.gz
    mvn -pl lab/sas-diameter-testapp package          -> sas-diameter-testapp-lab.jar
    cp configs/dra-peers.json .                       -> lab topology (edit: hss-a -> host hss-sim:3869)

Copy the three outputs here before `docker compose build`.
