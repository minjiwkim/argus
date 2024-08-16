from flask import Flask, jsonify, request

app = Flask(__name__)

@app.route('/')
def hello_pybo():
    return 'Hello, Pybo!'

#@app.route('/items/<int:item_id>', methods=['GET'])
#def get_item(item_id):
    # 예시 데이터 생성
#    item = {
#        'item_id': item_id,
#        'q': 'example query'
#    }
#    return jsonify(item)

#if __name__ == '__main__':
#    app.run(debug=True)
