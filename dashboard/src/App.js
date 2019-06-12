import React, {useState, useEffect} from 'react';
import Rx from 'rx-dom';

import {LineChart, Line, CartesianGrid, XAxis, YAxis} from 'recharts';

const RenderLineChart = ({data}) => (
  <LineChart width={600} height={300} data={data}>
    <Line type="monotone" dataKey="DELETE" stroke="#DE1738"/>
    <Line type="monotone" dataKey="POST" stroke="#00416A"/>
    <Line type="monotone" dataKey="GET" stroke="#51FF0D"/>
    <Line type="monotone" dataKey="PUT" stroke="#8884d8"/>
    <CartesianGrid stroke="#ccc"/>
    <XAxis dataKey="id"/>
    <YAxis/>
  </LineChart>
);
const App = () => {
  const [state, setState] = useState([]);
  useEffect(() => {
    const observer = Rx.Observer.create(function (e) {
      console.log('Opening');
    });

    const source = Rx.DOM.fromEventSource('http://localhost/result?simple=true', observer);
    source.subscribe((e) => {
      const data = JSON.parse(e);
      setState(prev => [...prev.slice(0, prev.length > 100 ? 100 : prev.length), {
        ...data.method,
        id: data.protocol["HTTP/1.0"]
      }]);
    })

  }, []);
  if (state == null || state.length < 1) return null;
  return (
    <div>
      <RenderLineChart data={state}/>

    </div>
  );
};

export default App;
