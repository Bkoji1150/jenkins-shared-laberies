def call(string name,string dayofweek) {
    // you can call any valid step functions from your code, just like you can from Pipeline scripts
    sh "echo Hello ${name} today is ${dayofweek}"
}
