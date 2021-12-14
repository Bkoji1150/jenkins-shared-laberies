// def call(String name,String dayofweek) {
//     // you can call any valid step functions from your code, just like you can from Pipeline scripts
//     sh "echo Hello ${name} today is ${dayofweek}"
// }

def call(Map config = [:]) {
    sh "echo hello ${config.name}. today is ${config.day_of_the_week}"
}