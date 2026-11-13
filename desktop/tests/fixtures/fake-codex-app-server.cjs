const readline = require('node:readline');
const send = value => process.stdout.write(`${JSON.stringify(value)}\n`);
let dynamic = false;
readline.createInterface({ input: process.stdin }).on('line', line => {
  const message = JSON.parse(line);
  if (message.method === 'initialize') send({ id: message.id, result: {} });
  if (message.method === 'thread/start') { dynamic = Array.isArray(message.params?.dynamicTools); send({ id: message.id, result: { thread: { id: 'fixture-thread' } } }); }
  if (message.method === 'turn/start') {
    send({ id: message.id, result: {} });
    send({ method: 'item/agentMessage/delta', params: { delta: 'First ' } });
    if (dynamic) send({ id: 'tool-one', method: 'item/tool/call', params: { tool: 'computer_ui', arguments: { action: 'list_windows' } } });
    else send({ id: 'approval-one', method: 'item/commandExecution/requestApproval', params: { command: 'fixture command', cwd: 'fixture directory' } });
  }
  if (message.id === 'approval-one') {
    send({ method: 'item/agentMessage/delta', params: { delta: message.result?.decision === 'accept' ? 'approved' : 'denied' } });
    send({ method: 'turn/completed', params: { turn: { status: 'completed' } } });
  }
  if (message.id === 'tool-one') {
    send({ method: 'item/agentMessage/delta', params: { delta: message.result?.success ? 'tool returned' : 'tool failed' } });
    send({ method: 'turn/completed', params: { turn: { status: 'completed' } } });
  }
});
