[ -d luigi_log ] || mkdir luigi_log
luigid --background --pidfile luigi.pid --logdir luigi_log --state-path state.pickle