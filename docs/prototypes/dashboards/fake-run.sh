#!/usr/bin/env bash
# Plays a fake "Nightly build & ship" Run that posts to its own dashboard.
B=${1:-http://localhost:8765}/api/dashboards
D=$B/nightly
c() { curl -s -X POST "$1" -d "$2" >/dev/null; }
c $B '{"id":"nightly","name":"Nightly build & ship"}'
for w in '"tests","number","Tests passing",0,0,3,2' '"tickets","progress","Tickets done",3,0,3,2' '"tasks","status","Tasks",6,0,6,4' \
         '"tokens","chart","Tokens per Task",0,2,6,4' '"log","log","Run log",6,4,6,4'; do
  IFS=, read id type title x y w h <<<"$w"; c $D/widgets "{\"id\":$id,\"type\":$type,\"title\":$title,\"x\":$x,\"y\":$y,\"w\":$w,\"h\":$h}"
done
tasks=(Architect "Write spec" "Split tickets" Implement Review Ship)
for run in 1 2; do
  for i in "${!tasks[@]}"; do
    items=$(for j in "${!tasks[@]}"; do ((j>i)) && continue; s=ok; ((j==i)) && s=running; printf '{"name":"%s","state":"%s"},' "${tasks[$j]}" "$s"; done)
    c $D/widgets/tasks "{\"items\":[${items%,}]}"
    c $D/widgets/log "{\"line\":\"run $run: ${tasks[$i]} started\"}"
    c $D/widgets/tokens "{\"points\":[{\"x\":$((run*10+i)),\"y\":$((RANDOM%4000+1000)),\"series\":\"input\"},{\"x\":$((run*10+i)),\"y\":$((RANDOM%1500+200)),\"series\":\"output\"}]}"
    c $D/widgets/tickets "{\"done\":$(( (i*8)/5 )),\"total\":8}"
    c $D/widgets/tests "{\"value\":$((85+RANDOM%15)),\"unit\":\"%\",\"delta\":$((RANDOM%7-3))}"
    sleep 1
  done
done
