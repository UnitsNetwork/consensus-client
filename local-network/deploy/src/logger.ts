import * as w from 'winston';
import * as path from 'path';

const PROJECT_ROOT = process.cwd();
const logDir = process.env['DEPLOY_LOG_DIR'] || `${PROJECT_ROOT}/../logs/deploy/`;

const levels: { [key: string]: string } = {
  error: 'E',
  info: 'I',
  debug: 'D',
  verbose: 'T',
};

const logger = w.createLogger({
  level: 'debug',
  format: w.format.combine(
    w.format.timestamp({ format: 'HH:mm:ss' }),
    w.format.splat(), // %o and other formatting: https://console.spec.whatwg.org/#formatting-specifiers
    w.format.printf((info: w.LogEntry & { at?: string }) => `${info.timestamp} [${levels[info.level]}] ${info.at}: ${info.message.split('\n').map(x => x.replace(/^\s+/, '')).join(' ')}`)
  ),
  transports: [
    new w.transports.Console(),
    new w.transports.File({ filename: `${logDir}/deploy.log` })
  ],
  exitOnError: false,
});

export function error(message: string, ...meta: any[]) {
  logger.error(message, ...addCallSite(meta));
}

export function info(message: string, ...meta: any[]) {
  logger.info(message, ...addCallSite(meta));
}

export function debug(message: string, ...meta: any[]) {
  logger.debug(message, ...addCallSite(meta));
}

export function verbose(message: string, ...meta: any[]) {
  logger.verbose(message, ...addCallSite(meta));
}

function addCallSite(meta: any[]): any[] {
  const stackInfo = getStackInfo(1);
  if (stackInfo) {
    const callSite = `${stackInfo.relativePath}:${stackInfo.line}`;
    meta.push({ at: callSite });
  }
  return meta;
}

interface StackInfo {
  relativePath: string;
  line: string;
}

/**
 * Parses and returns info about the call stack at the given index.
 */
function getStackInfo(stackIndex: number): StackInfo | undefined {
  const stacklist = new Error().stack?.split('\n').slice(3);
  if (!stacklist) return undefined;

  const stackReg = /at\s+(.*)\s+\((.*):(\d*):(\d*)\)/gi;
  const stackReg2 = /at\s+()(.*):(\d*):(\d*)/gi;

  const s = stacklist[stackIndex] || stacklist[0];
  const sp = stackReg.exec(s) || stackReg2.exec(s);

  if (sp && sp.length === 5) {
    return {
      relativePath: path.relative(PROJECT_ROOT, sp[2]).replace(`file:${PROJECT_ROOT}`, ''),
      line: sp[3],
    };
  }
  return undefined;
}
