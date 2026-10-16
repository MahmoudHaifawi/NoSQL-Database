#!/bin/bash
# Start the full cluster (3 data nodes + coordinator + gateway) from one image, built once.
# Gateway UI: http://localhost:8078/login   (seeded admin: mahmoud / admin).  Stop with ./stop.sh
docker compose up --build
