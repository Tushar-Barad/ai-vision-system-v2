#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled!
#endif

BluetoothSerial SerialBT;

const int BTN1 = 4;
const int BTN2 = 5;
const int BTN3 = 18;
const int BTN4 = 19;
const int BTN5 = 21;
const int BTN6 = 22;
const int BTN7 = 23;
const int LED = 2;

int lastState[7] = {HIGH, HIGH, HIGH, HIGH, HIGH, HIGH, HIGH};
int currState[7];
unsigned long lastDebounce[7] = {0,0,0,0,0,0,0};
const int debounceDelay = 50;
bool pressed[7] = {false,false,false,false,false,false,false};
bool phoneConnected = false;

void setup() {
  Serial.begin(115200);
  SerialBT.begin("BT7_SmartStick");
  Serial.println("BT7 Smart Stick ESP32 Ready");
  Serial.println("Bluetooth Device Name: BT7_SmartStick");
  Serial.println("Button 1 (GPIO 4)  -> Vision AI");
  Serial.println("Button 2 (GPIO 5)  -> Text Reader");
  Serial.println("Button 3 (GPIO 18) -> Currency");
  Serial.println("Button 4 (GPIO 19) -> Emergency");
  Serial.println("Button 5 (GPIO 21) -> Assistant");
  Serial.println("Button 6 (GPIO 22) -> Navigation");
  Serial.println("Button 7 (GPIO 23) -> Scanner");
  pinMode(BTN1, INPUT_PULLUP);
  pinMode(BTN2, INPUT_PULLUP);
  pinMode(BTN3, INPUT_PULLUP);
  pinMode(BTN4, INPUT_PULLUP);
  pinMode(BTN5, INPUT_PULLUP);
  pinMode(BTN6, INPUT_PULLUP);
  pinMode(BTN7, INPUT_PULLUP);
  pinMode(LED, OUTPUT);
  digitalWrite(LED, LOW);
  for(int i=0;i<3;i++){digitalWrite(LED,HIGH);delay(150);digitalWrite(LED,LOW);delay(150);}
  Serial.println("System Ready! Waiting for phone...");
}

void loop() {
  if (SerialBT.hasClient() && !phoneConnected) {
    phoneConnected = true;
    Serial.println("Phone Connected!");
    for(int i=0;i<5;i++){digitalWrite(LED,HIGH);delay(100);digitalWrite(LED,LOW);delay(100);}
  }
  if (!SerialBT.hasClient() && phoneConnected) {
    phoneConnected = false;
    Serial.println("Phone Disconnected.");
    digitalWrite(LED, LOW);
  }
  checkButton(0,BTN1,"VISION","Vision AI");
  checkButton(1,BTN2,"TEXT","Text Reader");
  checkButton(2,BTN3,"CURRENCY","Currency");
  checkButton(3,BTN4,"EMERGENCY","Emergency");
  checkButton(4,BTN5,"ASSISTANT","Assistant");
  checkButton(5,BTN6,"NAVIGATE","Navigation");
  checkButton(6,BTN7,"SCANNER","Scanner");
  if (SerialBT.available()) {
    String msg = SerialBT.readStringUntil('\n');
    msg.trim();
    if (msg.length() > 0) { Serial.print("Received: "); Serial.println(msg); }
  }
  delay(10);
}

void checkButton(int idx, int pin, const char* cmd, const char* name) {
  int reading = digitalRead(pin);
  if (reading != lastState[idx]) lastDebounce[idx] = millis();
  if ((millis() - lastDebounce[idx]) > debounceDelay) {
    if (reading != currState[idx]) {
      currState[idx] = reading;
      if (currState[idx] == LOW && !pressed[idx]) { pressed[idx]=true; handleButtonPress(cmd,name); }
      if (currState[idx] == HIGH) pressed[idx]=false;
    }
  }
  lastState[idx] = reading;
}

void handleButtonPress(const char* cmd, const char* name) {
  Serial.print("Button pressed: "); Serial.println(name);
  digitalWrite(LED, HIGH);
  if (phoneConnected) {
    SerialBT.println(cmd);
    Serial.print("Command sent: "); Serial.println(cmd);
    for(int i=0;i<3;i++){digitalWrite(LED,LOW);delay(80);digitalWrite(LED,HIGH);delay(80);}
  } else {
    Serial.println("ERROR: Phone not connected!");
    for(int i=0;i<6;i++){digitalWrite(LED,LOW);delay(50);digitalWrite(LED,HIGH);delay(50);}
  }
  digitalWrite(LED, LOW);
}