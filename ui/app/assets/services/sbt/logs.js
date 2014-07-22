define([
  'commons/websocket',
  './app'
], function(
  websocket,
  app
) {

  var logs = ko.observableArray([]);
  var stdout = ko.observableArray([]);

  // Websocket Handlers
  websocket
    .subscribe({ type: 'sbt', subType: 'LogEvent' })
    // Filter debug on demand
    .filter(function(m) {
      return !((m.event.entry.level == "debug" || m.event.entry.type == "stdout") && !(app.settings.showLogDebug() || debug))
    })
    .each(function(message){
      logs.push(message);
      // TODO: Put a higher scrollback
      if(logs().length > (app.settings.showLogDebug()?1000:250)) {
        logs.splice(0,100);
      }
    });

  websocket
    .subscribe({ type: 'sbt', subType: 'LogEvent', event: { entry: { message: String, type: "stdout" } } })
    .each(function(message){
      stdout.push(message);
      // TODO: Put a higher scrollback
      if(stdout().length > 500) {
        stdout.splice(0,100);
      }
    });

  return {
    logs: logs,
    stdout: stdout
  }

});
