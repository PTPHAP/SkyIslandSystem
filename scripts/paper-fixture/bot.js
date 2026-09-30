const mineflayer = require(process.env.SI_MINEFLAYER)
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const messages = []
const bot = mineflayer.createBot({host:'127.0.0.1',port:25579,username:'SI070Player',version:'1.20.1',auth:'offline'})
bot.on('messagestr', m => messages.push(m))
bot.on('error', e => process.stderr.write(e.message+'\n'))
async function run() {
  await new Promise((resolve,reject)=>{bot.once('spawn',resolve);bot.once('error',reject)})
  bot.chat('/skyisland register');await delay(300)
  bot.chat(process.env.SI_FIXTURE_PASSWORD+' '+process.env.SI_FIXTURE_PASSWORD)
  await delay(1800)
  if(!messages.some(m=>m.includes('注册成功')))throw new Error('offline identity not verified')
  process.stdout.write('BOT_VERIFIED\n')
  await delay(73000)
  bot.chat('/skyisland tasks');bot.chat('/skyisland task 070f0010');await delay(1500)
  if(!messages.some(m=>m.includes('070f0010')))throw new Error('player activity commands missing')
  bot.quit();process.stdout.write('BOT_COMMANDS_PASS\n')
}
run().catch(e=>{process.stderr.write(e.stack+'\n');bot.quit();process.exitCode=1})
